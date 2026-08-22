import { createHash } from 'node:crypto';
import {
  appendFileSync,
  lstatSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import process from 'node:process';
import { inflateRawSync } from 'node:zlib';

const sourceRepository = 'DevPathAi/devpath-ai-svc';
const gitopsRepository = 'DevPathAi/devpath-gitops';
const workflowPath = '.github/workflows/mission-spine-release-eval.yml';
const candidateWorkflow = '.github/workflows/mission-spine-candidate.yml';
const environmentName = 'mission-spine-ai-release-eval';
const jobName = 'Run AI release evaluation';
const MAX_API_BYTES = 1024 * 1024;
const MAX_ZIP_BYTES = 1024 * 1024;
const MAX_CANDIDATE_BYTES = 256 * 1024;
const releaseIdPattern = /^ms-[0-9]{8}-[a-z0-9][a-z0-9-]{2,40}$/;
const sha64Pattern = /^(?!0{64}$)[0-9a-f]{64}$/;
const teamSlug = /^[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?$/;
const reviewerLogin = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/;

function fail(message) {
  throw new Error(`Mission Spine release evaluation authorization failed: ${message}`);
}

function exact(actual, expected, name) {
  if (actual !== expected) fail(`${name} mismatch`);
}

function positiveInteger(value, name) {
  const parsed = typeof value === 'number' ? value : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || `${parsed}` !== `${value}`) {
    fail(`${name} must be a positive integer`);
  }
  return parsed;
}

function gitSha(value, name) {
  if (typeof value !== 'string' || !/^(?!0{40}$)[0-9a-f]{40}$/.test(value)) {
    fail(`${name} must be a nonzero lowercase Git SHA`);
  }
  return value;
}

function utc(value, name) {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) ||
    !Number.isFinite(Date.parse(value))
  ) {
    fail(`${name} must be an exact UTC timestamp ending in Z`);
  }
  return Date.parse(value);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function digest(value, name) {
  if (typeof value !== 'string') fail(`${name} is absent`);
  const normalized = value.startsWith('sha256:') ? value.slice(7) : value;
  if (!sha64Pattern.test(normalized)) fail(`${name} must be a nonzero lowercase SHA-256`);
  return normalized;
}

function artifactDigest(value, name) {
  if (typeof value !== 'string'
      || !/^sha256:(?!0{64}$)[0-9a-f]{64}$/.test(value)) {
    fail(`${name} must be a nonzero lowercase sha256:-prefixed digest`);
  }
  return value.slice(7);
}

function releaseId(value) {
  if (typeof value !== 'string' || !releaseIdPattern.test(value)) {
    fail('release_id is not a safe identifier');
  }
  return value;
}

function exactKeys(value, expected, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${name} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((entry, index) => entry !== wanted[index])) {
    fail(`${name} exact key set mismatch`);
  }
}

function decodeUtf8(bytes, name) {
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    fail(`${name} must be UTF-8 without BOM`);
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (error) {
    fail(`${name} must be strict UTF-8: ${error.message}`);
  }
}

export function parseStrictJsonBytes(input, name = 'JSON', maximumBytes = MAX_CANDIDATE_BYTES) {
  const bytes = Buffer.from(input);
  if (bytes.length < 2 || bytes.length > maximumBytes) fail(`${name} byte size is invalid`);
  const text = decodeUtf8(bytes, name);
  let index = 0;

  function whitespace() {
    while (index < text.length && /[\u0020\u000a\u000d\u0009]/.test(text[index])) index += 1;
  }

  function string() {
    if (text[index] !== '"') fail(`${name} contains malformed JSON string`);
    const start = index;
    index += 1;
    while (index < text.length) {
      const code = text.charCodeAt(index);
      if (code === 0x22) {
        index += 1;
        try {
          return JSON.parse(text.slice(start, index));
        } catch (error) {
          fail(`${name} contains invalid JSON string: ${error.message}`);
        }
      }
      if (code < 0x20) fail(`${name} contains a control character in a JSON string`);
      if (code === 0x5c) {
        index += 1;
        if (index >= text.length || !/["\\/bfnrtu]/.test(text[index])) {
          fail(`${name} contains an invalid JSON escape`);
        }
        if (text[index] === 'u') {
          if (!/^[0-9a-fA-F]{4}$/.test(text.slice(index + 1, index + 5))) {
            fail(`${name} contains an invalid Unicode escape`);
          }
          index += 4;
        }
      }
      index += 1;
    }
    fail(`${name} contains an unterminated JSON string`);
  }

  function value() {
    whitespace();
    if (text[index] === '"') return string();
    if (text[index] === '{') return object();
    if (text[index] === '[') return array();
    for (const [literal, result] of [['true', true], ['false', false], ['null', null]]) {
      if (text.startsWith(literal, index)) {
        index += literal.length;
        return result;
      }
    }
    const match = text.slice(index).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
    if (!match) fail(`${name} contains an invalid JSON value`);
    index += match[0].length;
    const result = Number(match[0]);
    if (!Number.isFinite(result)) fail(`${name} contains a non-finite number`);
    return result;
  }

  function array() {
    index += 1;
    whitespace();
    const result = [];
    if (text[index] === ']') {
      index += 1;
      return result;
    }
    while (true) {
      result.push(value());
      whitespace();
      if (text[index] === ']') {
        index += 1;
        return result;
      }
      if (text[index] !== ',') fail(`${name} contains malformed JSON array`);
      index += 1;
    }
  }

  function object() {
    index += 1;
    whitespace();
    const result = {};
    const seen = new Set();
    if (text[index] === '}') {
      index += 1;
      return result;
    }
    while (true) {
      whitespace();
      const key = string();
      if (seen.has(key)) fail(`${name} contains duplicate JSON key ${JSON.stringify(key)}`);
      seen.add(key);
      whitespace();
      if (text[index] !== ':') fail(`${name} contains malformed JSON object`);
      index += 1;
      Object.defineProperty(result, key, {
        value: value(), enumerable: true, configurable: true, writable: true,
      });
      whitespace();
      if (text[index] === '}') {
        index += 1;
        return result;
      }
      if (text[index] !== ',') fail(`${name} contains malformed JSON object`);
      index += 1;
    }
  }

  const result = value();
  whitespace();
  if (index !== text.length) fail(`${name} has trailing non-whitespace bytes`);
  return result;
}

const CANDIDATE_TOP_KEYS = [
  '$schema',
  'schema_version',
  'document_type',
  'release_id',
  'created_at',
  'gitops',
  'services',
  'shared_migration',
  'frontend',
  'home',
  'analytics_privacy',
  'ai_release_eval_config',
  'environments',
  'journey_harness',
  'quality_evidence_inputs',
  'rollout',
];
const GITOPS_KEYS = [
  'repository', 'base_sha', 'base_web_tag', 'base_web_digest', 'web_kustomization',
];
const AI_CONFIG_KEYS = [
  'primary_model',
  'fallback_models',
  'prompt_sha256',
  'fixture_revision',
  'fixture_sha256',
  'rendered_config_sha256',
  'ollama_endpoint_sha256',
];

function candidateContract(bytes, expectedReleaseId, candidateSpecSha256) {
  releaseId(expectedReleaseId);
  const expectedSha = digest(candidateSpecSha256, 'candidate_spec_sha256');
  const raw = Buffer.from(bytes);
  exact(sha256(raw), expectedSha, 'candidate raw SHA-256');
  const value = parseStrictJsonBytes(raw, 'candidate-spec.json');
  exactKeys(value, CANDIDATE_TOP_KEYS, 'candidate');
  if (typeof value.$schema !== 'string' || value.$schema.length < 1 || value.$schema.length > 256) {
    fail('candidate $schema is invalid');
  }
  exact(value.schema_version, 1, 'candidate schema_version');
  exact(value.document_type, 'candidate-spec', 'candidate document_type');
  exact(value.release_id, expectedReleaseId, 'candidate release_id');
  utc(value.created_at, 'candidate created_at');
  exactKeys(value.gitops, GITOPS_KEYS, 'candidate gitops');
  exact(value.gitops.repository, gitopsRepository, 'candidate gitops.repository');
  gitSha(value.gitops.base_sha, 'candidate gitops.base_sha');
  gitSha(value.gitops.base_web_tag, 'candidate gitops.base_web_tag');
  if (!/^sha256:[0-9a-f]{64}$/.test(value.gitops.base_web_digest ?? '')) {
    fail('candidate gitops.base_web_digest is invalid');
  }
  exact(
    value.gitops.web_kustomization,
    'apps/devpath-web/base/kustomization.yaml',
    'candidate gitops.web_kustomization',
  );
  if (value.services === null || typeof value.services !== 'object' || Array.isArray(value.services)) {
    fail('candidate services must be an object');
  }
  const service = value.services['devpath-ai-svc'];
  if (service === null || typeof service !== 'object' || Array.isArray(service)) {
    fail('candidate devpath-ai-svc service is absent');
  }
  exactKeys(service, ['repository', 'source_sha', 'image_repository', 'image_digest'], 'candidate AI service');
  exact(service.repository, sourceRepository, 'candidate AI service repository');
  gitSha(service.source_sha, 'candidate AI service source_sha');
  exactKeys(value.ai_release_eval_config, AI_CONFIG_KEYS, 'candidate AI config');
  const config = value.ai_release_eval_config;
  if (
    typeof config.primary_model !== 'string' ||
    !Array.isArray(config.fallback_models) ||
    config.fallback_models.length < 1 ||
    config.fallback_models.some((item) => typeof item !== 'string') ||
    new Set(config.fallback_models).size !== config.fallback_models.length ||
    config.fallback_models.includes(config.primary_model) ||
    typeof config.fixture_revision !== 'string' ||
    config.fixture_revision.length < 2
  ) {
    fail('candidate AI model or fixture configuration is invalid');
  }
  for (const field of [
    'prompt_sha256',
    'fixture_sha256',
    'rendered_config_sha256',
    'ollama_endpoint_sha256',
  ]) {
    digest(config[field], `candidate AI config ${field}`);
  }
  return value;
}

export function validateCandidateWorkflowDispatch(input) {
  const bytes = Buffer.from(input);
  const text = decodeUtf8(bytes, 'candidate producer workflow');
  if (text.includes('\r') || text.includes('\t')) {
    fail('candidate producer workflow must use canonical LF indentation');
  }
  const lines = text.split('\n');
  const onIndexes = lines.map((line, index) => line === 'on:' ? index : -1)
    .filter((index) => index >= 0);
  if (onIndexes.length !== 1) fail('candidate workflow must have exactly one canonical top-level on');
  const onIndex = onIndexes[0];
  const permissionsIndex = lines.indexOf('permissions:', onIndex + 1);
  if (permissionsIndex < 0) fail('candidate workflow permissions boundary is absent');
  const section = lines.slice(onIndex + 1, permissionsIndex);
  while (section.at(-1) === '') section.pop();
  if (section[0] !== '  workflow_dispatch:' || section[1] !== '    inputs:') {
    fail('candidate workflow must directly contain only workflow_dispatch inputs');
  }
  let index = 2;
  const inputs = [];
  while (index < section.length) {
    const input = section[index]?.match(/^      ([A-Za-z_][A-Za-z0-9_-]*):$/)?.[1];
    if (!input) fail('candidate workflow has a noncanonical dispatch child');
    index += 1;
    const properties = new Map();
    while (index < section.length && section[index].startsWith('        ')) {
      const property = section[index].match(/^        ([a-z_]+):(?: (.*))?$/);
      if (!property || properties.has(property[1])) {
        fail('candidate workflow input property is invalid or duplicated');
      }
      if (!['description', 'required', 'type'].includes(property[1])) {
        fail('candidate workflow input property is not allowlisted');
      }
      properties.set(property[1], property[2] ?? '');
      index += 1;
    }
    if (properties.get('required') !== 'true' || properties.get('type') !== 'string') {
      fail('candidate workflow input must be a required string');
    }
    if (properties.has('description') && properties.get('description').length < 1) {
      fail('candidate workflow input description must be nonempty');
    }
    inputs.push(input);
  }
  if (inputs.length !== 1 || inputs[0] !== 'release_id') {
    fail('candidate workflow dispatch input must be exactly release_id');
  }
  return true;
}

const CRC32_TABLE = new Uint32Array(256);
for (let index = 0; index < CRC32_TABLE.length; index += 1) {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) {
    value = (value >>> 1) ^ (0xedb88320 & -(value & 1));
  }
  CRC32_TABLE[index] = value >>> 0;
}

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) value = (value >>> 8) ^ CRC32_TABLE[(value ^ byte) & 0xff];
  return (value ^ 0xffffffff) >>> 0;
}

function zipName(bytes, expectedName) {
  const value = decodeUtf8(bytes, 'ZIP entry name');
  if (value !== expectedName || value.includes('/') || value.includes('\\') || value.includes('\0')) {
    fail(`unsafe or non-root artifact ZIP entry: ${JSON.stringify(value)}`);
  }
  return value;
}

function endOfCentralDirectory(bytes) {
  const minimum = Math.max(0, bytes.length - (65_535 + 22));
  for (let offset = bytes.length - 22; offset >= minimum; offset -= 1) {
    if (bytes.readUInt32LE(offset) === 0x06054b50) return offset;
  }
  fail('candidate artifact ZIP end-of-central-directory is absent');
}

function inflate(method, compressed, maximumBytes) {
  if (method === 0) return Buffer.from(compressed);
  if (method !== 8) fail('candidate artifact ZIP compression method is unsupported');
  try {
    return inflateRawSync(compressed, { maxOutputLength: maximumBytes });
  } catch (error) {
    fail(`candidate artifact ZIP cannot be safely inflated: ${error.message}`);
  }
}

export function candidateBytesFromZip(zipBytes) {
  const bytes = Buffer.from(zipBytes);
  if (bytes.length < 100 || bytes.length > MAX_ZIP_BYTES) {
    fail('candidate artifact ZIP byte length is invalid');
  }
  const endOffset = endOfCentralDirectory(bytes);
  if (endOffset + 22 !== bytes.length || bytes.readUInt16LE(endOffset + 20) !== 0) {
    fail('candidate artifact ZIP must have no comment or trailing bytes');
  }
  if (bytes.readUInt16LE(endOffset + 4) !== 0 || bytes.readUInt16LE(endOffset + 6) !== 0) {
    fail('candidate artifact ZIP must be single-disk');
  }
  if (bytes.readUInt16LE(endOffset + 8) !== 1 || bytes.readUInt16LE(endOffset + 10) !== 1) {
    fail('candidate artifact ZIP must contain exactly one candidate-spec.json entry');
  }
  const centralSize = bytes.readUInt32LE(endOffset + 12);
  const centralOffset = bytes.readUInt32LE(endOffset + 16);
  if (centralOffset + centralSize !== endOffset || centralOffset + 46 > endOffset
      || bytes.readUInt32LE(centralOffset) !== 0x02014b50) {
    fail('candidate artifact ZIP central bounds are malformed');
  }
  const cursor = centralOffset;
  const madeBy = bytes.readUInt16LE(cursor + 4);
  const flags = bytes.readUInt16LE(cursor + 8);
  const method = bytes.readUInt16LE(cursor + 10);
  const checksum = bytes.readUInt32LE(cursor + 16);
  const compressedSize = bytes.readUInt32LE(cursor + 20);
  const uncompressedSize = bytes.readUInt32LE(cursor + 24);
  const nameLength = bytes.readUInt16LE(cursor + 28);
  const extraLength = bytes.readUInt16LE(cursor + 30);
  const commentLength = bytes.readUInt16LE(cursor + 32);
  const diskStart = bytes.readUInt16LE(cursor + 34);
  const externalAttributes = bytes.readUInt32LE(cursor + 38);
  const localOffset = bytes.readUInt32LE(cursor + 42);
  const centralEnd = cursor + 46 + nameLength + extraLength + commentLength;
  if (centralEnd !== endOffset) fail('candidate artifact ZIP central entry bounds mismatch');
  const name = zipName(bytes.subarray(cursor + 46, cursor + 46 + nameLength), 'candidate-spec.json');
  if (
    flags & ~0x0808 || flags & 0x0001 || ![0, 8].includes(method) || diskStart !== 0 ||
    extraLength !== 0 || commentLength !== 0 ||
    [compressedSize, uncompressedSize, localOffset].includes(0xffffffff)
  ) {
    fail('candidate artifact ZIP entry metadata is unsafe or noncanonical');
  }
  const host = madeBy >>> 8;
  const mode = externalAttributes >>> 16;
  const type = mode & 0xf000;
  if (host === 3 && type !== 0 && type !== 0x8000) {
    fail('candidate artifact ZIP entry is a link or special file');
  }
  if (localOffset !== 0 || uncompressedSize < 2 || uncompressedSize > MAX_CANDIDATE_BYTES) {
    fail('candidate artifact ZIP candidate-spec.json size or offset is invalid');
  }
  if (bytes.readUInt32LE(0) !== 0x04034b50) fail('candidate artifact ZIP local header is malformed');
  const localFlags = bytes.readUInt16LE(6);
  const localMethod = bytes.readUInt16LE(8);
  const localChecksum = bytes.readUInt32LE(14);
  const localCompressedSize = bytes.readUInt32LE(18);
  const localUncompressedSize = bytes.readUInt32LE(22);
  const localNameLength = bytes.readUInt16LE(26);
  const localExtraLength = bytes.readUInt16LE(28);
  const localName = zipName(bytes.subarray(30, 30 + localNameLength), 'candidate-spec.json');
  if (localName !== name || localFlags !== flags || localMethod !== method || localExtraLength !== 0) {
    fail('candidate artifact ZIP local/central metadata mismatch');
  }
  const dataStart = 30 + localNameLength;
  const dataEnd = dataStart + compressedSize;
  if (dataEnd > centralOffset) fail('candidate artifact ZIP entry exceeds local-data bounds');
  let rangeEnd = dataEnd;
  if (flags & 0x0008) {
    let descriptor = dataEnd;
    if (bytes.readUInt32LE(descriptor) === 0x08074b50) descriptor += 4;
    if (descriptor + 12 > centralOffset) fail('candidate artifact ZIP descriptor is truncated');
    if (bytes.readUInt32LE(descriptor) !== checksum
        || bytes.readUInt32LE(descriptor + 4) !== compressedSize
        || bytes.readUInt32LE(descriptor + 8) !== uncompressedSize) {
      fail('candidate artifact ZIP descriptor mismatch');
    }
    rangeEnd = descriptor + 12;
  } else if (localChecksum !== checksum || localCompressedSize !== compressedSize
      || localUncompressedSize !== uncompressedSize) {
    fail('candidate artifact ZIP local sizes or CRC mismatch');
  }
  if (rangeEnd !== centralOffset) fail('candidate artifact ZIP local entry has gaps or trailing bytes');
  const body = inflate(method, bytes.subarray(dataStart, dataEnd), MAX_CANDIDATE_BYTES);
  if (body.length !== uncompressedSize || crc32(body) !== checksum) {
    fail('candidate artifact ZIP candidate-spec.json bytes or CRC mismatch');
  }
  return body;
}

function validateRunPath(path) {
  exact(path, workflowPath, 'run.path');
}

function validateProtectedBranch(branch, expectedSha, label) {
  exact(branch?.name, 'main', `${label} branch name`);
  exact(branch?.commit?.sha, expectedSha, `${label} branch commit SHA`);
  exact(branch?.protected, true, `${label} branch protection`);
}

export function validateReleaseEvalAuthorizationFacts(facts) {
  exact(facts.repository, sourceRepository, 'repository');
  const sourceSha = gitSha(facts.sourceSha, 'sourceSha');
  const gitopsSha = gitSha(facts.gitopsSha, 'gitopsSha');
  const runId = positiveInteger(facts.runId, 'runId');
  const runAttempt = positiveInteger(facts.runAttempt, 'runAttempt');
  if (runAttempt !== 1) {
    fail('release evaluation requires attempt 1 and a fresh workflow_dispatch');
  }
  exact(facts.environmentName, environmentName, 'environmentName');
  exact(facts.jobName, jobName, 'jobName');
  exact(facts.workflowPath, workflowPath, 'workflowPath');

  exact(facts.run?.id, runId, 'run.id');
  exact(facts.run?.run_attempt, runAttempt, 'run.run_attempt');
  exact(facts.run?.event, 'workflow_dispatch', 'run.event');
  exact(facts.run?.status, 'in_progress', 'run.status');
  exact(facts.run?.conclusion, null, 'run.conclusion');
  exact(facts.run?.head_sha, sourceSha, 'run.head_sha');
  exact(facts.run?.head_branch, 'main', 'run.head_branch');
  exact(facts.run?.repository?.full_name, sourceRepository, 'run.repository');
  exact(
    facts.run?.head_repository?.full_name,
    sourceRepository,
    'run.head_repository',
  );
  validateRunPath(facts.run?.path);
  exact(facts.sourceRepositoryInfo?.full_name, sourceRepository, 'source repository metadata');
  exact(facts.sourceRepositoryInfo?.default_branch, 'main', 'source repository default branch');
  exact(facts.sourceRepositoryInfo?.archived, false, 'source repository archived state');
  validateProtectedBranch(facts.sourceBranch, sourceSha, 'source');
  validateProtectedBranch(facts.gitopsBranch, gitopsSha, 'GitOps');

  exact(facts.environment?.name, environmentName, 'environment.name');
  const approvalEnvironmentId = positiveInteger(
    facts.environment?.id,
    'environment.id',
  );
  if (!Array.isArray(facts.environment?.protection_rules)) {
    fail('environment protection rules are absent');
  }
  const requiredReviewers = facts.environment.protection_rules.filter(
    (rule) => rule.type === 'required_reviewers',
  );
  if (requiredReviewers.length !== 1) {
    fail('required reviewer rule is absent or ambiguous');
  }
  const reviewerRule = requiredReviewers[0];
  exact(reviewerRule.prevent_self_review, true, 'environment prevent_self_review');
  if (!Array.isArray(reviewerRule.reviewers) || reviewerRule.reviewers.length < 1) {
    fail('environment has no configured reviewer');
  }

  if (!Array.isArray(facts.approvals)) fail('approval history is absent');
  const approvals = facts.approvals.filter(
    (approval) =>
      approval.state === 'approved' &&
      Array.isArray(approval.environments) &&
      approval.environments.filter(
        (entry) =>
          entry?.id === approvalEnvironmentId && entry?.name === environmentName,
      ).length === 1,
  );
  if (approvals.length !== 1) {
    fail('exact environment approval is absent or ambiguous');
  }
  const approval = approvals[0];
  const approvedBy = approval.user?.login;
  if (
    typeof approvedBy !== 'string' ||
    !reviewerLogin.test(approvedBy) ||
    approval.user?.type !== 'User'
  ) {
    fail('approved reviewer login is absent or non-human');
  }
  const approvedById = positiveInteger(approval.user.id, 'approved reviewer id');
  const initiators = [facts.run?.actor, facts.run?.triggering_actor];
  for (const [index, initiator] of initiators.entries()) {
    positiveInteger(initiator?.id, `run initiator ${index} id`);
    if (initiator?.type !== 'User' || !reviewerLogin.test(initiator?.login ?? '')) {
      fail(`run initiator ${index} must be a human GitHub user`);
    }
  }
  if (
    approvedBy === facts.run?.actor?.login ||
    approvedBy === facts.run?.triggering_actor?.login ||
    approvedById === facts.run?.actor?.id ||
    approvedById === facts.run?.triggering_actor?.id
  ) {
    fail('the workflow initiator cannot approve the release evaluation');
  }
  const configuredUser = reviewerRule.reviewers.some(
    (entry) => entry.type === 'User' && entry.reviewer?.id === approvedById
      && entry.reviewer?.login === approvedBy,
  );
  const configuredTeam = reviewerRule.reviewers.some(
    (entry) =>
      entry.type === 'Team' &&
      Number.isSafeInteger(entry.reviewer?.id) &&
      entry.reviewer.id > 0 &&
      teamSlug.test(entry.reviewer?.slug ?? '') &&
      Array.isArray(facts.teamMemberships) &&
      facts.teamMemberships.some(
        (membership) =>
          membership.teamId === entry.reviewer.id &&
          membership.teamSlug === entry.reviewer.slug &&
          membership.approvedById === approvedById &&
          membership.approvedBy === approvedBy &&
          membership.state === 'active' &&
          ['member', 'maintainer'].includes(membership.role),
      ),
  );
  if (!configuredUser && !configuredTeam) {
    fail('approved reviewer is not an exact configured user or active team member');
  }

  if (!facts.jobs || !Array.isArray(facts.jobs.jobs)) {
    fail('attempt job list is absent');
  }
  const jobs = facts.jobs.jobs.filter((job) => job.name === jobName);
  if (jobs.length !== 1) fail('protected job identity is absent or ambiguous');
  const job = jobs[0];
  exact(job.run_id, runId, 'approval job run_id');
  exact(job.head_sha, sourceSha, 'approval job head_sha');
  exact(job.status, 'in_progress', 'approval job status');
  exact(job.conclusion, null, 'approval job conclusion');
  const createdAt = utc(facts.run?.created_at, 'run.created_at');
  const approvalEffectiveAt = utc(job.started_at, 'approval job started_at');
  if (approvalEffectiveAt < createdAt || approvalEffectiveAt > Date.now()) {
    fail('approval effective time is outside the protected run interval');
  }

  if (!Buffer.isBuffer(facts.workflowBytes) || facts.workflowBytes.length < 1) {
    fail('workflow raw bytes are absent');
  }
  exact(
    sha256(facts.workflowBytes),
    facts.localWorkflowSha256,
    'workflow raw SHA-256',
  );

  return {
    approval_environment: environmentName,
    approval_environment_id: approvalEnvironmentId,
    approval_job_name: jobName,
    approved_by: approvedBy,
    approved_by_id: approvedById,
    approval_effective_at: job.started_at,
  };
}

function candidateBranch(value, expectedReleaseId) {
  if (
    typeof value !== 'string' || value.length < 1 || value.length > 255 ||
    value === 'main' || value.startsWith('/') || value.startsWith('.') ||
    value.endsWith('/') || value.endsWith('.') || value.endsWith('.lock') ||
    /[\\~^:?*\[\x00-\x1f\x7f]/.test(value) || value.includes('..') ||
    value.includes('//') || value.includes('@{')
  ) {
    fail('candidate run must originate from a safe non-main branch');
  }
  exact(value, `release/candidate-${expectedReleaseId}`, 'candidate run head_branch');
  return value;
}

export function validateCandidateArtifactFacts({
  expectedReleaseId,
  candidateSpecSha256,
  aiSourceSha,
  gitopsSourceSha,
  run,
  artifact,
  zipBytes,
}) {
  releaseId(expectedReleaseId);
  digest(candidateSpecSha256, 'candidate_spec_sha256');
  gitSha(aiSourceSha, 'ai_source_sha');
  gitSha(gitopsSourceSha, 'gitops_source_sha');
  const runId = positiveInteger(run?.id, 'candidate run.id');
  const runAttempt = positiveInteger(run?.run_attempt, 'candidate run.run_attempt');
  exact(run.event, 'workflow_dispatch', 'candidate run.event');
  exact(run.status, 'completed', 'candidate run.status');
  exact(run.conclusion, 'success', 'candidate run.conclusion');
  exact(run.path, candidateWorkflow, 'candidate run.path');
  const headSha = gitSha(run.head_sha, 'candidate run.head_sha');
  candidateBranch(run.head_branch, expectedReleaseId);
  exact(run.repository?.full_name, gitopsRepository, 'candidate run.repository');
  exact(run.head_repository?.full_name, gitopsRepository, 'candidate run.head_repository');

  const artifactId = positiveInteger(artifact?.id, 'candidate artifact.id');
  exact(
    artifact.name,
    `${expectedReleaseId}-candidate-spec-run-${runId}-attempt-${runAttempt}`,
    'candidate artifact.name',
  );
  exact(artifact.expired, false, 'candidate artifact.expired');
  const expiresAt = utc(artifact.expires_at, 'candidate artifact.expires_at');
  if (expiresAt <= Date.now()) fail('candidate artifact is expired by timestamp');
  const archiveSize = positiveInteger(
    artifact.size_in_bytes,
    'candidate artifact.size_in_bytes',
  );
  if (archiveSize > MAX_ZIP_BYTES) fail('candidate artifact ZIP exceeds 1 MiB');
  const archiveDigest = artifactDigest(
    artifact.digest,
    'candidate artifact digest',
  );
  exact(artifact.workflow_run?.id, runId, 'candidate artifact.workflow_run.id');
  exact(artifact.workflow_run?.head_sha, headSha, 'candidate artifact.workflow_run.head_sha');
  const archive = Buffer.from(zipBytes);
  exact(archive.length, archiveSize, 'candidate artifact ZIP size');
  exact(sha256(archive), archiveDigest, 'candidate artifact ZIP SHA-256');
  const candidateBytes = candidateBytesFromZip(archive);
  const candidate = candidateContract(
    candidateBytes,
    expectedReleaseId,
    candidateSpecSha256,
  );
  exact(candidate.gitops.base_sha, gitopsSourceSha, 'candidate gitops.base_sha/input');
  exact(
    candidate.services['devpath-ai-svc'].source_sha,
    aiSourceSha,
    'candidate AI source/input',
  );
  return {
    runId,
    runAttempt,
    headSha,
    artifactId,
    artifactName: artifact.name,
    artifactArchiveSha256: archiveDigest,
    candidate,
    candidateBytes,
    baseSha: candidate.gitops.base_sha,
  };
}

export function validateCandidateSourceFacts({
  expectedReleaseId,
  selected,
  repositoryInfo,
  mainBranch,
  headCommit,
  comparison,
  candidateBlobBytes,
  baseWorkflowBytes,
  headWorkflowBytes,
}) {
  releaseId(expectedReleaseId);
  exact(repositoryInfo?.full_name, gitopsRepository, 'GitOps repository full_name');
  exact(repositoryInfo?.default_branch, 'main', 'GitOps repository default branch');
  exact(repositoryInfo?.archived, false, 'GitOps repository archived state');
  exact(mainBranch?.name, 'main', 'GitOps protected main name');
  exact(mainBranch?.protected, true, 'GitOps protected main policy');
  exact(mainBranch?.commit?.sha, selected.baseSha, 'GitOps current protected main SHA');
  exact(headCommit?.sha, selected.headSha, 'candidate head commit SHA');
  if (!Array.isArray(headCommit?.parents) || headCommit.parents.length !== 1
      || headCommit.parents[0]?.sha !== selected.baseSha) {
    fail('candidate head must have exactly one parent equal to gitops.base_sha');
  }
  exact(comparison?.status, 'ahead', 'candidate comparison status');
  exact(comparison?.ahead_by, 1, 'candidate comparison ahead_by');
  exact(comparison?.behind_by, 0, 'candidate comparison behind_by');
  exact(comparison?.total_commits, 1, 'candidate comparison total_commits');
  exact(comparison?.merge_base_commit?.sha, selected.baseSha, 'candidate comparison merge base');
  if (!Array.isArray(comparison?.commits) || comparison.commits.length !== 1
      || comparison.commits[0]?.sha !== selected.headSha) {
    fail('candidate comparison must contain exactly the candidate head commit');
  }
  const expectedPath =
    `release-manifests/candidates/${expectedReleaseId}.candidate-spec.json`;
  if (!Array.isArray(comparison?.files) || comparison.files.length !== 1
      || comparison.files[0]?.filename !== expectedPath
      || comparison.files[0]?.status !== 'added'
      || comparison.files[0]?.previous_filename !== undefined) {
    fail('candidate commit must add only the exact canonical candidate path');
  }
  if (!Buffer.from(candidateBlobBytes).equals(selected.candidateBytes)) {
    fail('candidate artifact bytes differ from the committed candidate blob');
  }
  if (!Buffer.isBuffer(baseWorkflowBytes) || baseWorkflowBytes.length < 1
      || !Buffer.isBuffer(headWorkflowBytes)
      || !baseWorkflowBytes.equals(headWorkflowBytes)) {
    fail('candidate producer workflow differs from protected main');
  }
  validateCandidateWorkflowDispatch(headWorkflowBytes);
  return true;
}

export function selectUniqueCandidateMatch(matches) {
  if (!Array.isArray(matches) || matches.length !== 1) {
    fail('candidate discovery must find exactly one eligible current successful run');
  }
  positiveInteger(matches[0]?.runId, 'selected candidate run ID');
  return matches[0];
}

function githubUrl(path) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) {
    fail('GitHub API path is unsafe');
  }
  return `https://api.github.com${path}`;
}

async function githubResponse(path, token, fetchImpl = fetch) {
  if (typeof token !== 'string' || token.length < 1) fail('GitHub API token is absent');
  return fetchImpl(githubUrl(path), {
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28',
    },
    redirect: 'error',
  });
}

async function responseJson(response, path) {
  const length = response.headers?.get?.('content-length');
  if (length !== null && length !== undefined
      && (!/^[0-9]+$/.test(length) || Number(length) > MAX_API_BYTES)) {
    fail(`GitHub API ${path} content length is invalid`);
  }
  const raw = await response.text();
  if (Buffer.byteLength(raw, 'utf8') > MAX_API_BYTES) {
    fail(`GitHub API ${path} response is too large`);
  }
  return parseStrictJsonBytes(Buffer.from(raw), `GitHub API ${path}`, MAX_API_BYTES);
}

async function github(path, token, fetchImpl = fetch) {
  const response = await githubResponse(path, token, fetchImpl);
  if (!response.ok) fail(`GitHub API ${path} returned HTTP ${response.status}`);
  return responseJson(response, path);
}

async function githubOptional(path, token, fetchImpl = fetch) {
  const response = await githubResponse(path, token, fetchImpl);
  if (response.status === 404) return null;
  if (!response.ok) fail(`GitHub API ${path} returned HTTP ${response.status}`);
  return responseJson(response, path);
}

export async function listAllGitHubPages({ path, field, token, fetchImpl = fetch }) {
  if (typeof field !== 'string' || !/^[a-z_]+$/.test(field)) {
    fail('GitHub pagination field is invalid');
  }
  const base = new URL(githubUrl(path));
  if (base.origin !== 'https://api.github.com') fail('GitHub pagination origin is invalid');
  const results = [];
  const ids = new Set();
  let totalCount = null;
  for (let page = 1; page <= 100; page += 1) {
    base.searchParams.set('per_page', '100');
    base.searchParams.set('page', `${page}`);
    const value = await github(`${base.pathname}${base.search}`, token, fetchImpl);
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      fail(`GitHub paginated ${field} response must be an object`);
    }
    if (!Number.isSafeInteger(value.total_count) || value.total_count < 0
        || value.total_count > 10_000) {
      fail(`GitHub paginated ${field} total_count is invalid`);
    }
    if (totalCount === null) totalCount = value.total_count;
    exact(value.total_count, totalCount, `GitHub paginated ${field} total_count`);
    const entries = value[field];
    if (!Array.isArray(entries) || entries.length > 100) {
      fail(`GitHub paginated ${field} page is invalid`);
    }
    for (const entry of entries) {
      const id = positiveInteger(entry?.id, `GitHub paginated ${field} item id`);
      if (ids.has(id)) fail(`GitHub paginated ${field} item is duplicated`);
      ids.add(id);
      results.push(entry);
    }
    if (results.length > totalCount) fail(`GitHub paginated ${field} exceeds total_count`);
    if (results.length === totalCount) return results;
    if (entries.length !== 100) fail(`GitHub paginated ${field} ended before total_count`);
  }
  fail(`GitHub paginated ${field} exceeds the page limit`);
}

async function boundedResponseBytes(response, maximumBytes, name) {
  const length = response.headers?.get?.('content-length');
  if (length !== null && length !== undefined
      && (!/^[0-9]+$/.test(length) || Number(length) > maximumBytes)) {
    fail(`${name} content length is invalid`);
  }
  if (response.body?.getReader) {
    const reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > maximumBytes) {
        await reader.cancel();
        fail(`${name} exceeds its byte limit`);
      }
      chunks.push(Buffer.from(value));
    }
    return Buffer.concat(chunks, total);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > maximumBytes) fail(`${name} exceeds its byte limit`);
  return bytes;
}

async function downloadArtifactZip(artifactId, token, fetchImpl = fetch) {
  positiveInteger(artifactId, 'candidate artifact download ID');
  const path = `/repos/${gitopsRepository}/actions/artifacts/${artifactId}/zip`;
  let response = await fetchImpl(githubUrl(path), {
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28',
    },
    redirect: 'manual',
  });
  if ([301, 302, 303, 307, 308].includes(response.status)) {
    const location = response.headers?.get?.('location');
    let target;
    try {
      target = new URL(location);
    } catch {
      fail('candidate artifact redirect URL is invalid');
    }
    if (target.protocol !== 'https:' || target.username || target.password) {
      fail('candidate artifact redirect must be credential-free HTTPS');
    }
    response = await fetchImpl(target, {
      headers: { Accept: 'application/zip' },
      redirect: 'error',
    });
  }
  if (!response.ok) fail(`candidate artifact ZIP download returned HTTP ${response.status}`);
  return boundedResponseBytes(response, MAX_ZIP_BYTES, 'candidate artifact ZIP');
}

function strictBase64(value, name) {
  if (typeof value !== 'string') fail(`${name} is absent`);
  const normalized = value.replace(/\s/g, '');
  if (normalized.length < 4 || normalized.length % 4 !== 0
      || !/^[A-Za-z0-9+/]+={0,2}$/.test(normalized)) {
    fail(`${name} is not canonical base64`);
  }
  const bytes = Buffer.from(normalized, 'base64');
  if (bytes.toString('base64') !== normalized) fail(`${name} is not canonical base64`);
  return bytes;
}

function contentFileBytes(content, expectedPath, maximumBytes, name) {
  exact(content?.type, 'file', `${name}.type`);
  exact(content?.path, expectedPath, `${name}.path`);
  exact(content?.encoding, 'base64', `${name}.encoding`);
  if (!Number.isSafeInteger(content?.size) || content.size < 1 || content.size > maximumBytes) {
    fail(`${name}.size is invalid`);
  }
  const bytes = strictBase64(content.content, `${name}.content`);
  exact(bytes.length, content.size, `${name}.decoded size`);
  return bytes;
}

function exactRunView(left, right, name) {
  for (const field of ['id', 'run_attempt', 'event', 'status', 'conclusion', 'head_sha', 'head_branch', 'path']) {
    exact(left?.[field], right?.[field], `${name}.${field}`);
  }
  exact(left?.repository?.full_name, right?.repository?.full_name, `${name}.repository`);
  exact(
    left?.head_repository?.full_name,
    right?.head_repository?.full_name,
    `${name}.head_repository`,
  );
}

export async function discoverCandidateArtifact({
  expectedReleaseId,
  candidateSpecSha256,
  aiSourceSha,
  gitopsSourceSha,
  token,
  fetchImpl = fetch,
}) {
  releaseId(expectedReleaseId);
  digest(candidateSpecSha256, 'candidate_spec_sha256');
  gitSha(aiSourceSha, 'ai_source_sha');
  gitSha(gitopsSourceSha, 'gitops_source_sha');
  if (typeof token !== 'string' || token.length < 1) fail('GITOPS_TOKEN is absent');
  const expectedBranch = `release/candidate-${expectedReleaseId}`;
  const workflowId = encodeURIComponent(candidateWorkflow);
  const runs = await listAllGitHubPages({
    path: `/repos/${gitopsRepository}/actions/workflows/${workflowId}/runs` +
      `?event=workflow_dispatch&status=completed&branch=${encodeURIComponent(expectedBranch)}` +
      '&exclude_pull_requests=true',
    field: 'workflow_runs',
    token,
    fetchImpl,
  });
  const matches = [];
  for (const listed of runs) {
    candidateBranch(listed?.head_branch, expectedReleaseId);
    exact(listed.event, 'workflow_dispatch', 'candidate listed run.event');
    exact(listed.status, 'completed', 'candidate listed run.status');
    exact(listed.path, candidateWorkflow, 'candidate listed run.path');
    exact(listed.repository?.full_name, gitopsRepository, 'candidate listed run.repository');
    exact(
      listed.head_repository?.full_name,
      gitopsRepository,
      'candidate listed run.head_repository',
    );
    if (listed.conclusion !== 'success') continue;
    const runId = positiveInteger(listed.id, 'candidate listed run.id');
    const listedAttempt = positiveInteger(
      listed.run_attempt,
      'candidate listed run.run_attempt',
    );
    const current = await github(
      `/repos/${gitopsRepository}/actions/runs/${runId}`,
      token,
      fetchImpl,
    );
    exactRunView(listed, current, 'candidate listed/current run');
    exact(current.run_attempt, listedAttempt, 'candidate current highest run_attempt');
    const attempt = await github(
      `/repos/${gitopsRepository}/actions/runs/${runId}/attempts/${listedAttempt}`,
      token,
      fetchImpl,
    );
    exactRunView(current, attempt, 'candidate current/attempt run');
    const expectedName =
      `${expectedReleaseId}-candidate-spec-run-${runId}-attempt-${listedAttempt}`;
    const artifacts = await listAllGitHubPages({
      path: `/repos/${gitopsRepository}/actions/runs/${runId}/artifacts`,
      field: 'artifacts',
      token,
      fetchImpl,
    });
    const named = artifacts.filter((artifact) => artifact?.name === expectedName);
    if (named.length === 0) continue;
    if (named.length !== 1) fail('candidate run has ambiguous exact-named artifacts');
    if (named[0].expired === true) continue;
    const artifactId = positiveInteger(named[0].id, 'candidate listed artifact.id');
    const metadata = await github(
      `/repos/${gitopsRepository}/actions/artifacts/${artifactId}`,
      token,
      fetchImpl,
    );
    for (const field of ['id', 'name', 'expired', 'size_in_bytes', 'digest', 'expires_at']) {
      exact(named[0][field], metadata[field], `candidate listed/metadata artifact.${field}`);
    }
    exact(
      named[0].workflow_run?.id,
      metadata.workflow_run?.id,
      'candidate listed/metadata artifact.workflow_run.id',
    );
    exact(
      named[0].workflow_run?.head_sha,
      metadata.workflow_run?.head_sha,
      'candidate listed/metadata artifact.workflow_run.head_sha',
    );
    const zipBytes = await downloadArtifactZip(artifactId, token, fetchImpl);
    exact(zipBytes.length, metadata.size_in_bytes, 'candidate artifact ZIP metadata size');
    exact(
      sha256(zipBytes),
      artifactDigest(metadata.digest, 'candidate artifact digest'),
      'candidate artifact ZIP metadata SHA-256',
    );
    const rawCandidate = candidateBytesFromZip(zipBytes);
    if (sha256(rawCandidate) !== candidateSpecSha256) continue;
    matches.push(validateCandidateArtifactFacts({
      expectedReleaseId,
      candidateSpecSha256,
      aiSourceSha,
      gitopsSourceSha,
      run: attempt,
      artifact: metadata,
      zipBytes,
    }));
  }
  const selected = selectUniqueCandidateMatch(matches);
  const repositoryInfo = await github(`/repos/${gitopsRepository}`, token, fetchImpl);
  const mainBranch = await github(`/repos/${gitopsRepository}/branches/main`, token, fetchImpl);
  const headCommit = await github(
    `/repos/${gitopsRepository}/commits/${selected.headSha}`,
    token,
    fetchImpl,
  );
  const comparison = await github(
    `/repos/${gitopsRepository}/compare/${selected.baseSha}...${selected.headSha}`,
    token,
    fetchImpl,
  );
  const candidatePath =
    `release-manifests/candidates/${expectedReleaseId}.candidate-spec.json`;
  const encodedCandidatePath = candidatePath.split('/')
    .map((part) => encodeURIComponent(part)).join('/');
  const candidateContent = await github(
    `/repos/${gitopsRepository}/contents/${encodedCandidatePath}?ref=${selected.headSha}`,
    token,
    fetchImpl,
  );
  const candidateBlobBytes = contentFileBytes(
    candidateContent,
    candidatePath,
    MAX_CANDIDATE_BYTES,
    'candidate source blob',
  );
  const encodedWorkflow = candidateWorkflow.split('/')
    .map((part) => encodeURIComponent(part)).join('/');
  const baseWorkflowContent = await github(
    `/repos/${gitopsRepository}/contents/${encodedWorkflow}?ref=${selected.baseSha}`,
    token,
    fetchImpl,
  );
  const headWorkflowContent = await github(
    `/repos/${gitopsRepository}/contents/${encodedWorkflow}?ref=${selected.headSha}`,
    token,
    fetchImpl,
  );
  const baseWorkflowBytes = contentFileBytes(
    baseWorkflowContent,
    candidateWorkflow,
    MAX_API_BYTES,
    'protected candidate workflow',
  );
  const headWorkflowBytes = contentFileBytes(
    headWorkflowContent,
    candidateWorkflow,
    MAX_API_BYTES,
    'candidate head workflow',
  );
  validateCandidateSourceFacts({
    expectedReleaseId,
    selected,
    repositoryInfo,
    mainBranch,
    headCommit,
    comparison,
    candidateBlobBytes,
    baseWorkflowBytes,
    headWorkflowBytes,
  });
  return selected;
}

function options(argv) {
  const result = new Map();
  for (const argument of argv) {
    if (!argument.startsWith('--') || !argument.includes('=')) {
      fail(`invalid option ${argument}`);
    }
    const split = argument.indexOf('=');
    const key = argument.slice(2, split);
    if (result.has(key)) fail(`duplicate --${key}`);
    result.set(key, argument.slice(split + 1));
  }
  const expected = new Set([
    'gitops-sha',
    'release-id',
    'candidate-spec-sha256',
    'candidate-output',
    'output',
  ]);
  if (result.size !== expected.size || [...result.keys()].some((key) => !expected.has(key))) {
    fail('candidate authentication options are not exact');
  }
  return result;
}

async function authenticate(parsed) {
  const sourceToken = process.env.GITHUB_TOKEN;
  const gitopsToken = process.env.GITOPS_TOKEN;
  if (!sourceToken || !gitopsToken) fail('source and GitOps tokens are required');
  const repository = process.env.GITHUB_REPOSITORY;
  const sourceSha = process.env.GITHUB_SHA;
  const gitopsSha = gitSha(parsed.get('gitops-sha'), 'gitops-sha');
  const expectedReleaseId = releaseId(parsed.get('release-id'));
  const candidateSpecSha256 = digest(
    parsed.get('candidate-spec-sha256'),
    'candidate-spec-sha256',
  );
  const candidateOutput = parsed.get('candidate-output');
  const output = parsed.get('output');
  const runId = positiveInteger(process.env.GITHUB_RUN_ID, 'GITHUB_RUN_ID');
  const runAttempt = positiveInteger(
    process.env.GITHUB_RUN_ATTEMPT,
    'GITHUB_RUN_ATTEMPT',
  );
  exact(repository, sourceRepository, 'GITHUB_REPOSITORY');
  exact(process.env.GITHUB_REF, 'refs/heads/main', 'GITHUB_REF');
  gitSha(sourceSha, 'GITHUB_SHA');
  const info = lstatSync(workflowPath);
  if (!info.isFile() || info.isSymbolicLink()) {
    fail('local workflow must be one regular non-link file');
  }
  const localWorkflowBytes = readFileSync(workflowPath);

  const [run, sourceRepositoryInfo, sourceBranch, gitopsBranch, environment] = await Promise.all([
    github(
      `/repos/${sourceRepository}/actions/runs/${runId}/attempts/${runAttempt}`,
      sourceToken,
    ),
    github(`/repos/${sourceRepository}`, sourceToken),
    github(`/repos/${sourceRepository}/branches/main`, sourceToken),
    github(`/repos/${gitopsRepository}/branches/main`, gitopsToken),
    github(
      `/repos/${sourceRepository}/environments/${encodeURIComponent(environmentName)}`,
      sourceToken,
    ),
  ]);
  const encodedWorkflow = workflowPath
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/');
  const workflow = await github(
    `/repos/${sourceRepository}/contents/${encodedWorkflow}?ref=${sourceSha}`,
    sourceToken,
  );
  const workflowBytes = contentFileBytes(
    workflow,
    workflowPath,
    MAX_API_BYTES,
    'release evaluation workflow',
  );
  if (!workflowBytes.equals(localWorkflowBytes)) {
    fail('checked workflow differs from exact GitHub source bytes');
  }

  let approvals = [];
  let jobs = null;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    approvals = await github(
      `/repos/${sourceRepository}/actions/runs/${runId}/approvals`,
      sourceToken,
    );
    jobs = {
      jobs: await listAllGitHubPages({
        path:
          `/repos/${sourceRepository}/actions/runs/${runId}` +
          `/attempts/${runAttempt}/jobs`,
        field: 'jobs',
        token: sourceToken,
      }),
    };
    const reviewVisible = Array.isArray(approvals) && approvals.some(
      (approval) =>
        approval.state === 'approved' &&
        approval.environments?.some(
          (entry) => entry.id === environment.id && entry.name === environmentName,
        ),
    );
    if (reviewVisible && jobs?.jobs?.some((job) => job.name === jobName && job.started_at)) {
      break;
    }
    await new Promise((done) => setTimeout(done, 2000));
  }

  const exactApprovals = Array.isArray(approvals) ? approvals.filter(
    (approval) =>
      approval.state === 'approved' &&
      Array.isArray(approval.environments) &&
      approval.environments.filter(
        (entry) => entry.id === environment.id && entry.name === environmentName,
      ).length === 1,
  ) : [];
  const teamMemberships = [];
  const reviewer = exactApprovals.length === 1 ? exactApprovals[0].user : null;
  const requiredReviewerRule = environment.protection_rules?.filter(
    (rule) => rule.type === 'required_reviewers',
  );
  if (
    reviewer?.type === 'User' &&
    Number.isSafeInteger(reviewer.id) &&
    reviewer.id > 0 &&
    reviewerLogin.test(reviewer.login ?? '') &&
    requiredReviewerRule?.length === 1 &&
    Array.isArray(requiredReviewerRule[0].reviewers)
  ) {
    for (const entry of requiredReviewerRule[0].reviewers) {
      if (
        entry.type !== 'Team' ||
        !Number.isSafeInteger(entry.reviewer?.id) ||
        entry.reviewer.id < 1 ||
        !teamSlug.test(entry.reviewer?.slug ?? '')
      ) {
        continue;
      }
      const membership = await githubOptional(
        `/orgs/DevPathAi/teams/${encodeURIComponent(entry.reviewer.slug)}` +
          `/memberships/${encodeURIComponent(reviewer.login)}`,
        sourceToken,
      );
      if (
        membership?.state === 'active' &&
        ['member', 'maintainer'].includes(membership.role)
      ) {
        teamMemberships.push({
          teamId: entry.reviewer.id,
          teamSlug: entry.reviewer.slug,
          approvedById: reviewer.id,
          approvedBy: reviewer.login,
          state: membership.state,
          role: membership.role,
        });
      }
    }
  }

  const result = validateReleaseEvalAuthorizationFacts({
    repository,
    sourceSha,
    gitopsSha,
    runId,
    runAttempt,
    environmentName,
    jobName,
    workflowPath,
    localWorkflowSha256: sha256(localWorkflowBytes),
    run,
    sourceRepositoryInfo,
    sourceBranch,
    gitopsBranch,
    environment,
    approvals,
    jobs,
    teamMemberships,
    workflowBytes,
  });
  const selectedCandidate = await discoverCandidateArtifact({
    expectedReleaseId,
    candidateSpecSha256,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
    token: gitopsToken,
  });
  writeFileSync(candidateOutput, selectedCandidate.candidateBytes, {
    flag: 'wx',
    mode: 0o600,
  });
  writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, {
    encoding: 'utf8',
    flag: 'wx',
  });
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(
      process.env.GITHUB_OUTPUT,
      Object.entries(result).map(([key, value]) => `${key}=${value}\n`).join(''),
      { encoding: 'utf8' },
    );
  }
  process.stdout.write('AI release evaluation authorization: authenticated\n');
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  authenticate(options(process.argv.slice(2))).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
