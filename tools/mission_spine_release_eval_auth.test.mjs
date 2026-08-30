import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  discoverCandidateArtifact,
  listAllGitHubPages,
  selectUniqueCandidateMatch,
  validateCandidateArtifactFacts,
  validateCandidateSourceFacts,
  validateCandidateWorkflowDispatch,
  validateReleaseEvalAuthorizationFacts,
} from './mission_spine_release_eval_auth.mjs';

const sourceSha = '1234567890abcdef1234567890abcdef12345678';
const gitopsSha = 'abcdef1234567890abcdef1234567890abcdef12';
const workflowPath = '.github/workflows/mission-spine-release-eval.yml';
const workflowBytes = Buffer.from('name: trusted AI release evaluation\n');
const workflowSha256 = createHash('sha256').update(workflowBytes).digest('hex');
const releaseId = 'ms-20260817-ai-eval';
const candidateHeadSha = '234567890abcdef1234567890abcdef123456789';
const candidateWorkflowBytes = Buffer.from(
  'name: Candidate\non:\n  workflow_dispatch:\n    inputs:\n' +
  '      release_id:\n        required: true\n        type: string\npermissions:\n  contents: read\n',
);

test('prod26r9 dispatcher starts the protected main evaluation as the Actions App', () => {
  const workflow = readFileSync(
    new URL('../.github/workflows/mission-spine-release-eval.yml', import.meta.url),
    'utf8',
  ).replace(/\r\n/g, '\n');
  assert.match(
    workflow,
    /release-eval:\n\s+if: github\.ref == 'refs\/heads\/main'/,
  );
  assert.match(
    workflow,
    /dispatch-release-eval:\n\s+if: github\.ref == 'refs\/heads\/chore\/prod26r9-ai-eval-dispatch'/,
  );
  assert.match(workflow, /actions: write/);
  assert.match(workflow, /RELEASE_ID: ms-20260830-prod26r9/);
  assert.match(
    workflow,
    /CANDIDATE_SPEC_SHA256: 9cc685a7ed8fda010ba8bf1b920afd0aed91fba00fb3ccb42a2b12208be1cff4/,
  );
  assert.match(
    workflow,
    /AI_SOURCE_SHA: 86288e1522a394d8d55bd162ca05c3bcc806cca7/,
  );
  assert.match(
    workflow,
    /GITOPS_SOURCE_SHA: 44d0e5f72fbd3c82e4499fc3c8587f5aff92d313/,
  );
  assert.match(workflow, /test "\$inner_actor" = "github-actions\[bot\]"/);
  assert.match(workflow, /test "\$inner_triggering_actor" = "github-actions\[bot\]"/);
  assert.match(workflow, /test "\$\(jq -er '\.actor\.id' "\$run_document"\)" = "41898282"/);
});

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function candidateBytes(overrides = {}) {
  const value = {
    $schema: '../schema-v1.json',
    schema_version: 1,
    document_type: 'candidate-spec',
    release_id: releaseId,
    created_at: '2026-08-17T12:00:00Z',
    gitops: {
      repository: 'DevPathAi/devpath-gitops',
      base_sha: gitopsSha,
      base_web_tag: '34567890abcdef1234567890abcdef1234567890-mission-on',
      base_web_digest: `sha256:${'1'.repeat(64)}`,
      web_kustomization: 'apps/devpath-web/base/kustomization.yaml',
    },
    services: {
      'devpath-ai-svc': {
        repository: 'DevPathAi/devpath-ai-svc',
        source_sha: sourceSha,
        image_repository: 'ghcr.io/devpathai/devpath-ai-svc',
        image_digest: `sha256:${'2'.repeat(64)}`,
      },
    },
    shared_migration: {},
    frontend: {},
    home: {},
    analytics_privacy: {},
    ai_release_eval_config: {
      primary_model: 'qwen2.5:3b',
      fallback_models: ['claude-sonnet-4-6'],
      prompt_sha256: '3'.repeat(64),
      fixture_revision: 'mentor-golden-v2',
      fixture_sha256: '4'.repeat(64),
      rendered_config_sha256: '5'.repeat(64),
      ollama_endpoint_sha256: '6'.repeat(64),
    },
    environments: {},
    journey_harness: {},
    quality_evidence_inputs: {},
    rollout: {},
    ...overrides,
  };
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
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

function storedZip(entries, mode = 0o100644) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, body] of entries) {
    const nameBytes = Buffer.from(name, 'utf8');
    const bytes = Buffer.from(body);
    const checksum = crc32(bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(bytes.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, bytes);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE((3 << 8) | 20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(bytes.length, 20);
    central.writeUInt32LE(bytes.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE((mode << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);
    offset += local.length + nameBytes.length + bytes.length;
  }
  const centralBytes = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBytes, end]);
}

function responseHeaders(values = {}) {
  const normalized = new Map(
    Object.entries(values).map(([key, value]) => [key.toLowerCase(), String(value)]),
  );
  return { get: (name) => normalized.get(name.toLowerCase()) ?? null };
}

function jsonResponse(value, status = 200) {
  const text = JSON.stringify(value);
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: responseHeaders({ 'content-length': Buffer.byteLength(text) }),
    async text() { return text; },
  };
}

function bytesResponse(bytes, status = 200, headers = {}) {
  const body = Buffer.from(bytes);
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: responseHeaders(headers),
    async arrayBuffer() {
      return body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength);
    },
  };
}

function facts(overrides = {}) {
  return {
    repository: 'DevPathAi/devpath-ai-svc',
    sourceSha,
    gitopsSha,
    runId: 501,
    runAttempt: 1,
    environmentName: 'mission-spine-ai-release-eval',
    jobName: 'Run AI release evaluation',
    workflowPath,
    localWorkflowSha256: workflowSha256,
    run: {
      id: 501,
      run_attempt: 1,
      event: 'workflow_dispatch',
      status: 'in_progress',
      conclusion: null,
      head_sha: sourceSha,
      head_branch: 'main',
      path: workflowPath,
      repository: { full_name: 'DevPathAi/devpath-ai-svc' },
      head_repository: { full_name: 'DevPathAi/devpath-ai-svc' },
      actor: { id: 11, login: 'initiator', type: 'User' },
      triggering_actor: { id: 11, login: 'initiator', type: 'User' },
      created_at: '2026-08-16T20:00:00Z',
    },
    sourceRepositoryInfo: {
      full_name: 'DevPathAi/devpath-ai-svc',
      default_branch: 'main',
      archived: false,
    },
    sourceBranch: {
      name: 'main',
      protected: true,
      commit: { sha: sourceSha },
    },
    gitopsBranch: {
      name: 'main',
      protected: true,
      commit: { sha: gitopsSha },
    },
    environment: {
      id: 91,
      name: 'mission-spine-ai-release-eval',
      protection_rules: [{
        type: 'required_reviewers',
        prevent_self_review: true,
        reviewers: [{
          type: 'User',
          reviewer: { id: 21, login: 'independent-reviewer' },
        }],
      }],
    },
    approvals: [{
      state: 'approved',
      user: { id: 21, login: 'independent-reviewer', type: 'User' },
      environments: [{ id: 91, name: 'mission-spine-ai-release-eval' }],
    }],
    jobs: { jobs: [{
      name: 'Run AI release evaluation',
      run_id: 501,
      head_sha: sourceSha,
      status: 'in_progress',
      conclusion: null,
      started_at: '2026-08-16T20:02:03Z',
    }] },
    workflowBytes,
    ...overrides,
  };
}

test('authorization binds current protected source, GitOps and configured review', () => {
  assert.deepEqual(validateReleaseEvalAuthorizationFacts(facts()), {
    approval_environment: 'mission-spine-ai-release-eval',
    approval_environment_id: 91,
    approval_job_name: 'Run AI release evaluation',
    approved_by: 'independent-reviewer',
    approved_by_id: 21,
    approval_effective_at: '2026-08-16T20:02:03Z',
  });
});

test('authorization permits the configured reviewer to initiate and approve', () => {
  const value = facts();
  value.run.actor = { id: 21, login: 'independent-reviewer', type: 'User' };
  value.run.triggering_actor = {
    id: 21,
    login: 'independent-reviewer',
    type: 'User',
  };
  assert.equal(
    validateReleaseEvalAuthorizationFacts(value).approved_by,
    'independent-reviewer',
  );
});

test('authorization accepts only the exact GitHub Actions automation initiator', () => {
  const automated = facts();
  automated.run.actor = {
    id: 41898282,
    login: 'github-actions[bot]',
    type: 'Bot',
  };
  automated.run.triggering_actor = structuredClone(automated.run.actor);
  assert.equal(
    validateReleaseEvalAuthorizationFacts(automated).approved_by,
    'independent-reviewer',
  );

  for (const identity of [
    { id: 41898283, login: 'github-actions[bot]', type: 'Bot' },
    { id: 41898282, login: 'github-actions-bot[bot]', type: 'Bot' },
    { id: 41898282, login: 'github-actions[bot]-suffix', type: 'Bot' },
    { id: 41898282, login: 'github-actions[bot]', type: 'User' },
  ]) {
    const invalid = facts();
    invalid.run.actor = identity;
    invalid.run.triggering_actor = structuredClone(identity);
    assert.throws(() => validateReleaseEvalAuthorizationFacts(invalid));
  }
});

test('authorization accepts a team reviewer only with exact active membership proof', () => {
  const value = facts({
    teamMemberships: [{
      teamId: 81,
      teamSlug: 'release-reviewers',
      approvedById: 21,
      approvedBy: 'independent-reviewer',
      state: 'active',
      role: 'member',
    }],
  });
  value.environment.protection_rules[0].reviewers = [{
    type: 'Team', reviewer: { id: 81, slug: 'release-reviewers' },
  }];
  assert.equal(
    validateReleaseEvalAuthorizationFacts(value).approved_by,
    'independent-reviewer',
  );

  value.teamMemberships[0].state = 'pending';
  assert.throws(() => validateReleaseEvalAuthorizationFacts(value));
  value.teamMemberships[0].state = 'active';
  value.teamMemberships[0].role = 'admin';
  assert.throws(() => validateReleaseEvalAuthorizationFacts(value));
});

test('authorization rejects branch drift, unprotected refs, invalid identities and reruns', () => {
  const mutations = [
    (value) => { value.run.id = 502; },
    (value) => { value.run.run_attempt = 2; value.runAttempt = 2; },
    (value) => { value.run.event = 'push'; },
    (value) => { value.run.status = 'completed'; },
    (value) => { value.run.head_sha = 'f'.repeat(40); },
    (value) => { value.run.head_branch = 'feature/attacker'; },
    (value) => { value.run.path = `${workflowPath}@main`; },
    (value) => { value.run.path = `${workflowPath}@feature/attacker`; },
    (value) => { value.run.path = `${workflowPath}@refs/heads/main`; },
    (value) => { value.run.repository.full_name = 'attacker/fork'; },
    (value) => { value.run.head_repository.full_name = 'attacker/fork'; },
    (value) => { value.run.actor = null; },
    (value) => { value.run.triggering_actor.type = 'Bot'; },
    (value) => { value.sourceRepositoryInfo.default_branch = 'develop'; },
    (value) => { value.sourceBranch.name = 'feature/attacker'; },
    (value) => { value.sourceBranch.protected = false; },
    (value) => { value.sourceBranch.commit.sha = 'f'.repeat(40); },
    (value) => { value.gitopsBranch.name = 'feature/attacker'; },
    (value) => { value.gitopsBranch.protected = false; },
    (value) => { value.gitopsBranch.commit.sha = 'e'.repeat(40); },
    (value) => { value.environment.protection_rules[0].prevent_self_review = false; },
    (value) => { value.approvals.push(structuredClone(value.approvals[0])); },
    (value) => { value.approvals[0].environments[0].id = 92; },
    (value) => { value.approvals[0].user.type = 'Bot'; },
    (value) => {
      value.environment.protection_rules[0].reviewers = [{
        type: 'Team', reviewer: { id: 81, slug: 'release-reviewers' },
      }];
    },
    (value) => { value.jobs.jobs.push(structuredClone(value.jobs.jobs[0])); },
    (value) => { value.jobs.jobs[0].run_id = 502; },
    (value) => { value.jobs.jobs[0].head_sha = 'e'.repeat(40); },
    (value) => { value.jobs.jobs[0].status = 'completed'; },
    (value) => { value.jobs.jobs[0].started_at = '2026-08-16T19:59:59Z'; },
    (value) => { value.localWorkflowSha256 = '0'.repeat(64); },
    (value) => { value.workflowBytes = Buffer.from('substituted workflow\n'); },
  ];
  for (const mutate of mutations) {
    const invalid = structuredClone(facts());
    invalid.workflowBytes = Buffer.from(workflowBytes);
    mutate(invalid);
    assert.throws(() => validateReleaseEvalAuthorizationFacts(invalid));
  }
});

test('authorization enumerates every protected-job page and rejects a later duplicate', async () => {
  const primary = { id: 1, ...structuredClone(facts().jobs.jobs[0]) };
  const firstPage = [
    primary,
    ...Array.from({ length: 99 }, (_, index) => ({
      id: index + 2,
      name: `Unrelated job ${index + 2}`,
    })),
  ];
  const duplicate = { ...structuredClone(primary), id: 101 };
  const jobs = await listAllGitHubPages({
    path: '/repos/DevPathAi/devpath-ai-svc/actions/runs/501/attempts/1/jobs',
    field: 'jobs',
    token: 'test-token',
    fetchImpl: async (input) => {
      const page = Number(new URL(input).searchParams.get('page'));
      return jsonResponse({
        total_count: 101,
        jobs: page === 1 ? firstPage : page === 2 ? [duplicate] : [],
      });
    },
  });
  assert.equal(jobs.length, 101);
  const value = facts();
  value.jobs = { jobs };
  assert.throws(
    () => validateReleaseEvalAuthorizationFacts(value),
    /job identity is absent or ambiguous/i,
  );
});

test('candidate artifact binds API-current attempt, exact ZIP, source and workflow', () => {
  const raw = candidateBytes();
  const candidateDigest = sha256(raw);
  const zip = storedZip([['candidate-spec.json', raw]]);
  const run = {
    id: 601,
    run_attempt: 2,
    event: 'workflow_dispatch',
    status: 'completed',
    conclusion: 'success',
    head_sha: candidateHeadSha,
    head_branch: `release/candidate-${releaseId}`,
    path: '.github/workflows/mission-spine-candidate.yml',
    repository: { full_name: 'DevPathAi/devpath-gitops' },
    head_repository: { full_name: 'DevPathAi/devpath-gitops' },
  };
  const artifact = {
    id: 701,
    name: `${releaseId}-candidate-spec-run-601-attempt-2`,
    expired: false,
    expires_at: '2099-01-01T00:00:00Z',
    size_in_bytes: zip.length,
    digest: `sha256:${sha256(zip)}`,
    workflow_run: { id: 601, head_sha: candidateHeadSha },
  };
  const common = {
    expectedReleaseId: releaseId,
    candidateSpecSha256: candidateDigest,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
  };
  const selected = validateCandidateArtifactFacts({
    ...common, run, artifact, zipBytes: zip,
  });
  assert.equal(selected.runAttempt, 2);
  assert.ok(selected.candidateBytes.equals(raw));
  assert.equal(selectUniqueCandidateMatch([selected]), selected);
  assert.throws(
    () => selectUniqueCandidateMatch([selected, { ...selected, runId: 602 }]),
    /exactly one eligible/i,
  );

  for (const invalidTag of [
    '34567890abcdef1234567890abcdef1234567890-mission-invalid',
    '34567890ABCDEF1234567890abcdef1234567890-mission-on',
    `${'0'.repeat(40)}-mission-off`,
  ]) {
    const invalidRaw = candidateBytes({
      gitops: {
        repository: 'DevPathAi/devpath-gitops',
        base_sha: gitopsSha,
        base_web_tag: invalidTag,
        base_web_digest: `sha256:${'1'.repeat(64)}`,
        web_kustomization: 'apps/devpath-web/base/kustomization.yaml',
      },
    });
    const invalidZip = storedZip([['candidate-spec.json', invalidRaw]]);
    const invalidArtifact = {
      ...artifact,
      size_in_bytes: invalidZip.length,
      digest: `sha256:${sha256(invalidZip)}`,
    };
    assert.throws(() => validateCandidateArtifactFacts({
      ...common,
      candidateSpecSha256: sha256(invalidRaw),
      run,
      artifact: invalidArtifact,
      zipBytes: invalidZip,
    }), /base_web_tag/i);
  }

  const sourceFacts = {
    expectedReleaseId: releaseId,
    selected,
    repositoryInfo: {
      full_name: 'DevPathAi/devpath-gitops', default_branch: 'main', archived: false,
    },
    mainBranch: { name: 'main', protected: true, commit: { sha: gitopsSha } },
    headCommit: { sha: candidateHeadSha, parents: [{ sha: gitopsSha }] },
    comparison: {
      status: 'ahead',
      ahead_by: 1,
      behind_by: 0,
      total_commits: 1,
      merge_base_commit: { sha: gitopsSha },
      commits: [{ sha: candidateHeadSha }],
      files: [{
        filename: `release-manifests/candidates/${releaseId}.candidate-spec.json`,
        status: 'added',
      }],
    },
    candidateBlobBytes: raw,
    baseWorkflowBytes: candidateWorkflowBytes,
    headWorkflowBytes: candidateWorkflowBytes,
  };
  assert.equal(validateCandidateSourceFacts(sourceFacts), true);

  const artifactMutations = [
    (value) => { value.run.event = 'push'; },
    (value) => { value.run.path = '.github/workflows/attacker.yml'; },
    (value) => { value.run.repository.full_name = 'attacker/fork'; },
    (value) => { value.run.head_repository.full_name = 'attacker/fork'; },
    (value) => { value.run.head_branch = 'main'; },
    (value) => { value.run.run_attempt = 1; },
    (value) => { value.artifact.name = `${releaseId}-candidate-spec-run-601-attempt-1`; },
    (value) => { value.artifact.expired = true; },
    (value) => { value.artifact.expires_at = '2020-01-01T00:00:00Z'; },
    (value) => { value.artifact.id = 0; },
    (value) => { value.artifact.digest = value.artifact.digest.slice(7); },
    (value) => { value.artifact.digest = `sha256:${'9'.repeat(64)}`; },
    (value) => { value.artifact.workflow_run.head_sha = gitopsSha; },
  ];
  for (const mutate of artifactMutations) {
    const changed = { run: structuredClone(run), artifact: structuredClone(artifact) };
    mutate(changed);
    assert.throws(() => validateCandidateArtifactFacts({
      ...common, run: changed.run, artifact: changed.artifact, zipBytes: zip,
    }));
  }

  const sourceMutations = [
    (value) => { value.repositoryInfo.default_branch = 'develop'; },
    (value) => { value.mainBranch.protected = false; },
    (value) => { value.mainBranch.commit.sha = candidateHeadSha; },
    (value) => { value.headCommit.parents[0].sha = candidateHeadSha; },
    (value) => { value.comparison.ahead_by = 2; },
    (value) => { value.comparison.files[0].status = 'modified'; },
    (value) => { value.comparison.files.push({ filename: 'extra.txt', status: 'added' }); },
    (value) => { value.candidateBlobBytes = Buffer.from('substituted\n'); },
    (value) => { value.headWorkflowBytes = Buffer.from('substituted\n'); },
  ];
  for (const mutate of sourceMutations) {
    const changed = structuredClone(sourceFacts);
    changed.selected.candidateBytes = Buffer.from(raw);
    changed.candidateBlobBytes = Buffer.from(raw);
    changed.baseWorkflowBytes = Buffer.from(candidateWorkflowBytes);
    changed.headWorkflowBytes = Buffer.from(candidateWorkflowBytes);
    mutate(changed);
    assert.throws(() => validateCandidateSourceFacts(changed));
  }

  for (const unsafe of [
    storedZip([['../candidate-spec.json', raw]]),
    storedZip([['candidate-spec.json', raw], ['extra.json', Buffer.from('{}\n')]]),
    storedZip([['candidate-spec.json', raw], ['candidate-spec.json', raw]]),
    storedZip([['candidate-spec.json', raw]], 0o120777),
    storedZip([['candidate-spec.json', Buffer.alloc(256 * 1024 + 1, 0x61)]]),
  ]) {
    const unsafeArtifact = {
      ...artifact,
      size_in_bytes: unsafe.length,
      digest: `sha256:${sha256(unsafe)}`,
    };
    assert.throws(() => validateCandidateArtifactFacts({
      ...common,
      run,
      artifact: unsafeArtifact,
      zipBytes: unsafe,
    }));
  }
  const encrypted = Buffer.from(zip);
  const encryptedCentral = encrypted.readUInt32LE(encrypted.length - 22 + 16);
  encrypted.writeUInt16LE(encrypted.readUInt16LE(6) | 1, 6);
  encrypted.writeUInt16LE(encrypted.readUInt16LE(encryptedCentral + 8) | 1, encryptedCentral + 8);
  const encryptedArtifact = {
    ...artifact,
    size_in_bytes: encrypted.length,
    digest: `sha256:${sha256(encrypted)}`,
  };
  assert.throws(() => validateCandidateArtifactFacts({
    ...common, run, artifact: encryptedArtifact, zipBytes: encrypted,
  }), /unsafe|encrypted/i);
  const corrupt = Buffer.from(zip);
  corrupt[30 + Buffer.byteLength('candidate-spec.json') + 5] ^= 0xff;
  const corruptArtifact = {
    ...artifact,
    size_in_bytes: corrupt.length,
    digest: `sha256:${sha256(corrupt)}`,
  };
  assert.throws(() => validateCandidateArtifactFacts({
    ...common, run, artifact: corruptArtifact, zipBytes: corrupt,
  }), /CRC|bytes/i);
});

test('candidate workflow parser rejects sibling events, extra inputs and defaults', () => {
  assert.equal(validateCandidateWorkflowDispatch(candidateWorkflowBytes), true);
  for (const mutation of [
    candidateWorkflowBytes.toString().replace(
      '  workflow_dispatch:',
      '  push:\n  workflow_dispatch:',
    ),
    candidateWorkflowBytes.toString().replace(
      '        type: string',
      '        type: string\n        default: unsafe',
    ),
    candidateWorkflowBytes.toString().replace(
      'permissions:',
      '      digest:\n        required: true\n        type: string\npermissions:',
    ),
    candidateWorkflowBytes.toString().replace('on:', "'on':"),
  ]) {
    assert.throws(() => validateCandidateWorkflowDispatch(Buffer.from(mutation)));
  }
});

test('candidate discovery paginates and rejects a competing eligible fresh run', async () => {
  const raw = candidateBytes();
  const rawDigest = sha256(raw);
  const zip = storedZip([['candidate-spec.json', raw]]);
  const zipDigest = sha256(zip);
  const branch = `release/candidate-${releaseId}`;
  const run = (id) => ({
    id,
    run_attempt: 2,
    event: 'workflow_dispatch',
    status: 'completed',
    conclusion: 'success',
    head_sha: candidateHeadSha,
    head_branch: branch,
    path: '.github/workflows/mission-spine-candidate.yml',
    repository: { full_name: 'DevPathAi/devpath-gitops' },
    head_repository: { full_name: 'DevPathAi/devpath-gitops' },
  });
  const artifact = (runId) => ({
    id: runId + 1000,
    name: `${releaseId}-candidate-spec-run-${runId}-attempt-2`,
    expired: false,
    expires_at: '2099-01-01T00:00:00Z',
    size_in_bytes: zip.length,
    digest: `sha256:${zipDigest}`,
    workflow_run: { id: runId, head_sha: candidateHeadSha },
  });
  function fetchFor(runIds, options = {}) {
    return async (input) => {
      const url = new URL(input);
      const path = url.pathname;
      if (url.hostname === 'artifact.example.invalid') return bytesResponse(zip);
      if (path.includes('/actions/workflows/') && path.endsWith('/runs')) {
        if (options.laterPage) {
          const page = Number(url.searchParams.get('page'));
          const failed = Array.from({ length: 99 }, (_, index) => ({
            ...run(10_000 + index), conclusion: 'failure',
          }));
          return jsonResponse({
            total_count: 101,
            workflow_runs: page === 1
              ? [run(runIds[0]), ...failed]
              : page === 2 ? [run(runIds[1])] : [],
          });
        }
        return jsonResponse({ total_count: runIds.length, workflow_runs: runIds.map(run) });
      }
      const attempt = path.match(/\/actions\/runs\/(\d+)\/attempts\/2$/);
      if (attempt) return jsonResponse(run(Number(attempt[1])));
      const current = path.match(/\/actions\/runs\/(\d+)$/);
      if (current) {
        const value = run(Number(current[1]));
        if (options.staleAttempt) value.run_attempt = 3;
        return jsonResponse(value);
      }
      const artifacts = path.match(/\/actions\/runs\/(\d+)\/artifacts$/);
      if (artifacts) {
        const value = artifact(Number(artifacts[1]));
        return jsonResponse({ total_count: 1, artifacts: [value] });
      }
      const metadata = path.match(/\/actions\/artifacts\/(\d+)$/);
      if (metadata) return jsonResponse(artifact(Number(metadata[1]) - 1000));
      if (/\/actions\/artifacts\/\d+\/zip$/.test(path)) {
        return bytesResponse(Buffer.alloc(0), 302, {
          location: 'https://artifact.example.invalid/candidate.zip',
        });
      }
      if (path === '/repos/DevPathAi/devpath-gitops') {
        return jsonResponse({
          full_name: 'DevPathAi/devpath-gitops', default_branch: 'main', archived: false,
        });
      }
      if (path.endsWith('/branches/main')) {
        return jsonResponse({ name: 'main', protected: true, commit: { sha: gitopsSha } });
      }
      if (path.endsWith(`/commits/${candidateHeadSha}`)) {
        return jsonResponse({ sha: candidateHeadSha, parents: [{ sha: gitopsSha }] });
      }
      if (path.endsWith(`/compare/${gitopsSha}...${candidateHeadSha}`)) {
        return jsonResponse({
          status: 'ahead', ahead_by: 1, behind_by: 0, total_commits: 1,
          merge_base_commit: { sha: gitopsSha }, commits: [{ sha: candidateHeadSha }],
          files: [{
            filename: `release-manifests/candidates/${releaseId}.candidate-spec.json`,
            status: 'added',
          }],
        });
      }
      if (path.includes('/contents/release-manifests/candidates/')) {
        return jsonResponse({
          type: 'file',
          path: `release-manifests/candidates/${releaseId}.candidate-spec.json`,
          encoding: 'base64', size: raw.length, content: raw.toString('base64'),
        });
      }
      if (path.includes('/contents/.github/workflows/mission-spine-candidate.yml')) {
        return jsonResponse({
          type: 'file', path: '.github/workflows/mission-spine-candidate.yml',
          encoding: 'base64', size: candidateWorkflowBytes.length,
          content: candidateWorkflowBytes.toString('base64'),
        });
      }
      throw new Error(`unexpected test URL: ${url}`);
    };
  }
  const selected = await discoverCandidateArtifact({
    expectedReleaseId: releaseId,
    candidateSpecSha256: rawDigest,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
    token: 'test-token',
    fetchImpl: fetchFor([601]),
  });
  assert.equal(selected.runId, 601);
  await assert.rejects(discoverCandidateArtifact({
    expectedReleaseId: releaseId,
    candidateSpecSha256: rawDigest,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
    token: 'test-token',
    fetchImpl: fetchFor([601, 602]),
  }), /exactly one eligible/i);
  await assert.rejects(discoverCandidateArtifact({
    expectedReleaseId: releaseId,
    candidateSpecSha256: rawDigest,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
    token: 'test-token',
    fetchImpl: fetchFor([601, 602], { laterPage: true }),
  }), /exactly one eligible/i);
  await assert.rejects(discoverCandidateArtifact({
    expectedReleaseId: releaseId,
    candidateSpecSha256: rawDigest,
    aiSourceSha: sourceSha,
    gitopsSourceSha: gitopsSha,
    token: 'test-token',
    fetchImpl: fetchFor([601], { staleAttempt: true }),
  }), /run_attempt/i);

  const firstPage = Array.from({ length: 100 }, (_, index) => ({ id: index + 1 }));
  const paginated = await listAllGitHubPages({
    path: '/repos/DevPathAi/devpath-gitops/actions/workflows/test/runs',
    field: 'workflow_runs',
    token: 'test-token',
    fetchImpl: async (input) => {
      const page = Number(new URL(input).searchParams.get('page'));
      return jsonResponse({
        total_count: 101,
        workflow_runs: page === 1 ? firstPage : page === 2 ? [{ id: 101 }] : [],
      });
    },
  });
  assert.equal(paginated.length, 101);
});
