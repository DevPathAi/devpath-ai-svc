import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devpath"
version = "0.0.1-SNAPSHOT"
description = "DevPath AI AI services (AI gateway orchestrator, review worker, FinOps)"

val devpathSharedVersion = providers.gradleProperty("devpathSharedVersion").get()
val devpathSharedCoordinate = "ai.devpath:devpath-shared:$devpathSharedVersion"

fun sha256(file: File): String = HexFormat.of().formatHex(
	MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	providers.gradleProperty("immutableSharedRepository").orNull?.let { repository ->
		maven { url = uri(repository) }
	}
	mavenCentral()
	maven {
		url = uri("https://maven.pkg.github.com/DevPathAi/devpath-shared")
		credentials {
			username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
			password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
		}
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.kafka:spring-kafka")
	implementation("org.springframework.boot:spring-boot-kafka")
	implementation(devpathSharedCoordinate)
	implementation("com.anthropic:anthropic-java:2.34.0")
	runtimeOnly("org.postgresql:postgresql")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.awaitility:awaitility")
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	testImplementation("org.springframework.boot:spring-boot-flyway")
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	val groups = System.getProperty("groups")
	systemProperty("immutableSharedContract",
		System.getProperty("immutableSharedContract") ?: "false")
	useJUnitPlatform {
		if (groups.isNullOrBlank()) {
			excludeTags("eval")
		} else if (groups == "eval") {
			includeTags("eval")
		}
	}
	if (groups == "eval") {
		outputs.upToDateWhen { false }
		outputs.cacheIf("live release eval evidence cannot be reused") { false }
		testLogging.showStandardStreams = true
	}
}

tasks.register<JavaExec>("generateMentorReleaseEvalManifest") {
	group = "verification"
	description = "Generate the exact hash-bound Mentor release evaluation manifest"
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("ai.devpath.aigw.mentor.eval.MentorReleaseEvalManifestCli")
	args("generate-manifest")
}

tasks.register<JavaExec>("verifyMentorReleaseEvalEvidence") {
	group = "verification"
	description = "Fail closed unless fresh Mentor release evaluation evidence matches the manifest"
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("ai.devpath.aigw.mentor.eval.MentorReleaseEvalManifestCli")
	args("verify-evidence")
}

tasks.register<JavaExec>("generateMissionSpineReleaseEvalEvidence") {
	group = "verification"
	description = "Adapt fresh ET9 evaluation results into sanitized Mission Spine evidence"
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("ai.devpath.aigw.mentor.eval.MissionSpineReleaseEvalEvidenceCli")
	args("generate")
}

tasks.register<JavaExec>("validateMissionSpineReleaseEvalCandidate") {
	group = "verification"
	description = "Fail closed unless the canonical candidate binds exact ET9 release inputs"
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("ai.devpath.aigw.mentor.eval.MissionSpineReleaseEvalEvidenceCli")
	args("preflight")
}

tasks.register<JavaExec>("validateMissionSpineReleaseEvalEvidence") {
	group = "verification"
	description = "Reopen and fail closed on Mission Spine release evaluation evidence drift"
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("ai.devpath.aigw.mentor.eval.MissionSpineReleaseEvalEvidenceCli")
	args("validate")
}

val runtimeClasspath = configurations.named("runtimeClasspath")
val bootJar = tasks.named<BootJar>("bootJar")

fun runtimeDependencyLines(): List<String> = runtimeClasspath.get()
	.resolvedConfiguration.resolvedArtifacts.map { artifact ->
		val id = artifact.moduleVersion.id
		"${id.group}:${artifact.name}:${id.version}|${artifact.file.name}|${sha256(artifact.file)}"
	}.sorted()

fun bootLibraryLines(file: File): List<String> = JarFile(file).use { archive ->
	archive.entries().asSequence()
		.filter { !it.isDirectory && it.name.startsWith("BOOT-INF/lib/") }
		.map { entry ->
			val hash = archive.getInputStream(entry).use { stream ->
				HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()))
			}
			"${entry.name.removePrefix("BOOT-INF/lib/")}|$hash"
		}
		.sorted()
		.toList()
}

val writeMentorCurrentRuntimeDependencyGraph =
	tasks.register("writeMentorCurrentRuntimeDependencyGraph") {
	group = "verification"
	description = "Write the exact currently resolved runtime dependency graph for release comparison"
	inputs.files(runtimeClasspath)
	val output = layout.buildDirectory.file("release-eval/current-runtime-dependency-graph.txt")
	outputs.file(output)
	doLast {
		val file = output.get().asFile
		file.parentFile.mkdirs()
		file.writeText(runtimeDependencyLines().joinToString("\n", postfix = "\n"))
	}
}

val prepareMentorReleaseArtifacts = tasks.register("prepareMentorReleaseArtifacts") {
	group = "build"
	description = "Bundle the exact bootJar, Shared artifact, runtime graph, and boot library graph"
	dependsOn(bootJar)
	inputs.files(runtimeClasspath)
	inputs.file(bootJar.flatMap { it.archiveFile })
	val output = layout.buildDirectory.dir("release-inputs")
	outputs.dir(output)
	doLast {
		val directory = output.get().asFile
		project.delete(directory)
		directory.mkdirs()

		val artifacts = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
		val shared = artifacts.singleOrNull { artifact ->
			artifact.moduleVersion.id.group == "ai.devpath" && artifact.name == "devpath-shared"
		} ?: throw GradleException("exact devpath-shared runtime artifact is missing or duplicated")
		if (shared.moduleVersion.id.version != devpathSharedVersion
				|| devpathSharedVersion.endsWith("SNAPSHOT")) {
			throw GradleException("devpath-shared must resolve to the immutable ET9 coordinate")
		}

		val sourceJar = bootJar.get().archiveFile.get().asFile
		val releaseJar = directory.resolve(sourceJar.name)
		sourceJar.copyTo(releaseJar, overwrite = true)
		val releaseShared = directory.resolve("devpath-shared-$devpathSharedVersion.jar")
		shared.file.copyTo(releaseShared, overwrite = true)
		directory.resolve("runtime-dependency-graph.txt")
			.writeText(runtimeDependencyLines().joinToString("\n", postfix = "\n"))
		directory.resolve("boot-library-graph.txt")
			.writeText(bootLibraryLines(releaseJar).joinToString("\n", postfix = "\n"))

		val nestedName = "BOOT-INF/lib/${releaseShared.name}"
		JarFile(releaseJar).use { archive ->
			val sharedEntries = archive.entries().asSequence()
				.filter { it.name.startsWith("BOOT-INF/lib/devpath-shared-") }.toList()
			if (sharedEntries.map { it.name } != listOf(nestedName)) {
				throw GradleException("bootJar does not contain exactly the immutable Shared artifact")
			}
			val nestedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(archive.getInputStream(sharedEntries.single()).readAllBytes()))
			if (nestedHash != sha256(releaseShared)) {
				throw GradleException("bootJar Shared artifact differs from the evaluated artifact")
			}
		}
	}
}

tasks.named<Test>("test") {
	exclude("**/MentorReleaseArtifactPipelineTest.class")
}

val mentorReleaseArtifactFunctionalTest =
	tasks.register<Test>("mentorReleaseArtifactFunctionalTest") {
		group = "verification"
		description = "Exercise bootJar to prepared graphs to manifest with real artifacts"
		dependsOn(prepareMentorReleaseArtifacts, writeMentorCurrentRuntimeDependencyGraph)
		testClassesDirs = sourceSets["test"].output.classesDirs
		classpath = sourceSets["test"].runtimeClasspath
		include("**/MentorReleaseArtifactPipelineTest.class")
		shouldRunAfter(tasks.named("test"))
		doFirst {
			systemProperty("mentorReleaseInputsDir",
				layout.buildDirectory.dir("release-inputs").get().asFile.absolutePath)
			systemProperty("mentorBootJar", bootJar.get().archiveFile.get().asFile.absolutePath)
			systemProperty("mentorCurrentRuntimeGraph", layout.buildDirectory
				.file("release-eval/current-runtime-dependency-graph.txt").get().asFile.absolutePath)
		}
	}

tasks.named("check") {
	dependsOn(mentorReleaseArtifactFunctionalTest)
}
