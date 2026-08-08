package atlas.indexer.bundle

import com.typesafe.config.{Config, ConfigFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}
import java.security.MessageDigest
import java.time.Instant
import scala.collection.JavaConverters._

final case class BundleComponent(name: String, path: String, sha256: String)

final case class SourceBundleManifest(
    manifestVersion: Int,
    bundleId: String,
    release: String,
    components: Seq[BundleComponent]
)

final case class ValidationAttestation(
    attestationVersion: Int,
    bundleId: String,
    bundleManifestSha256: String,
    validatorVersion: String,
    validationContractVersion: String,
    mode: String,
    result: String,
    completedAt: Instant,
    warningCodes: Seq[String],
    componentHashes: Map[String, String]
)

final case class ValidatedGoldBundle(
    generation: Path,
    manifestPath: Path,
    manifestSha256: String,
    manifest: SourceBundleManifest,
    attestation: ValidationAttestation,
    companyProfiles: Path,
    leads: Path
)

final class GoldBundleReader(
    bundleRoot: Path,
    allowedWarningCodes: Set[String] = Set.empty
) {
  private val RequiredComponents = Set("gold_company_profiles", "gold_leads_new_companies")
  private val BundleIdPattern = "^[0-9A-Za-z][0-9A-Za-z._-]*$".r
  private val HashPattern = "^[0-9a-f]{64}$".r

  def readCurrent(): ValidatedGoldBundle = {
    val pointer = readConfig(bundleRoot.resolve("current_bundle.json"), "current bundle pointer")
    readSelected(requiredString(pointer, "bundle_id", "current bundle pointer"),
      Some(requiredString(pointer, "release", "current bundle pointer")))
  }

  def readSelected(bundleId: String): ValidatedGoldBundle = readSelected(bundleId, None)

  private def readSelected(bundleId: String, pointerRelease: Option[String]): ValidatedGoldBundle = {
    validateBundleId(bundleId)
    val generations = bundleRoot.resolve("generations").toAbsolutePath.normalize()
    val generation = generations.resolve(bundleId).normalize()
    requireContainedDirectory(generations, generation, s"bundle generation $bundleId")

    val manifestPath = generation.resolve("bundle-manifest.json")
    val manifestConfig = readConfig(manifestPath, "bundle manifest")
    val manifest = readManifest(manifestConfig)
    require(manifest.manifestVersion == 1, s"Unsupported bundle manifest version: ${manifest.manifestVersion}")
    require(manifest.bundleId == bundleId,
      s"Bundle manifest ID ${manifest.bundleId} does not match selected bundle $bundleId")
    pointerRelease.foreach(value => require(value == manifest.release,
      s"Current pointer release $value does not match bundle release ${manifest.release}"))

    val duplicates = manifest.components.groupBy(_.name).collect { case (name, values) if values.size > 1 => name }
    require(duplicates.isEmpty, s"Duplicate bundle components: ${duplicates.toSeq.sorted.mkString(",")}")
    val byName = manifest.components.map(value => value.name -> value).toMap
    val missing = RequiredComponents -- byName.keySet
    require(missing.isEmpty, s"Missing required gold components: ${missing.toSeq.sorted.mkString(",")}")

    val manifestHash = fileSha256(manifestPath)
    val attestation = readAttestation(bundleId)
    validateAttestation(attestation, manifest, manifestHash, byName)
    val resolved = RequiredComponents.map { name =>
      val component = byName(name)
      name -> validateComponent(generation, component)
    }.toMap

    ValidatedGoldBundle(generation, manifestPath, manifestHash, manifest, attestation,
      resolved("gold_company_profiles"), resolved("gold_leads_new_companies"))
  }

  private def readManifest(value: Config): SourceBundleManifest = {
    val components = value.getConfigList("components").asScala.map { component =>
      BundleComponent(requiredString(component, "name", "bundle component"),
        requiredString(component, "path", "bundle component"),
        requiredHash(component, "sha256", "bundle component"))
    }
    SourceBundleManifest(value.getInt("manifest_version"),
      requiredString(value, "bundle_id", "bundle manifest"),
      requiredString(value, "release", "bundle manifest"), components)
  }

  private def readAttestation(bundleId: String): ValidationAttestation = {
    val path = bundleRoot.resolve("validation").resolve(s"$bundleId.json")
    val value = readConfig(path, "bundle validation attestation")
    val hashes = value.getConfigList("components").asScala.map { component =>
      requiredString(component, "name", "attested component") ->
        requiredHash(component, "sha256", "attested component")
    }
    require(hashes.map(_._1).distinct.size == hashes.size, "Duplicate attested component names")
    ValidationAttestation(value.getInt("attestation_version"),
      requiredString(value, "bundle_id", "validation attestation"),
      requiredHash(value, "bundle_manifest_sha256", "validation attestation"),
      requiredString(value, "validator_version", "validation attestation"),
      requiredString(value, "validation_contract_version", "validation attestation"),
      requiredString(value, "mode", "validation attestation"),
      requiredString(value, "result", "validation attestation"),
      Instant.parse(requiredString(value, "completed_at", "validation attestation")),
      if (value.hasPath("warning_codes")) value.getStringList("warning_codes").asScala.toSeq else Seq.empty,
      hashes.toMap)
  }

  private def validateAttestation(
      value: ValidationAttestation,
      manifest: SourceBundleManifest,
      manifestHash: String,
      components: Map[String, BundleComponent]
  ): Unit = {
    require(value.attestationVersion == 1,
      s"Unsupported validation attestation version: ${value.attestationVersion}")
    require(value.bundleId == manifest.bundleId, "Validation attestation bundle ID mismatch")
    require(value.bundleManifestSha256 == manifestHash, "Validation attestation manifest hash mismatch")
    require(value.mode == "full", s"Serving requires full bundle validation, observed: ${value.mode}")
    require(Set("PASS", "PASS_WITH_WARNINGS").contains(value.result),
      s"Bundle validation is not accepted: ${value.result}")
    val unsupportedWarnings = value.warningCodes.toSet -- allowedWarningCodes
    require(unsupportedWarnings.isEmpty,
      s"Bundle validation has non-allowlisted warnings: ${unsupportedWarnings.toSeq.sorted.mkString(",")}")
    RequiredComponents.foreach { name =>
      require(value.componentHashes.get(name).contains(components(name).sha256),
        s"Validation attestation component hash mismatch: $name")
    }
  }

  private def validateComponent(generation: Path, component: BundleComponent): Path = {
    val relative = Paths.get(component.path)
    require(!relative.isAbsolute, s"Component path must be relative: ${component.name}")
    val resolved = generation.resolve(relative).normalize()
    requireContainedDirectory(generation, resolved, s"component ${component.name}")
    val stream = Files.walk(resolved)
    val hasParquet = try stream.iterator().asScala.exists(path =>
      Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".parquet"))
    finally stream.close()
    require(hasParquet, s"Component contains no Parquet files: ${component.name}")
    val observed = directorySha256(resolved)
    require(observed == component.sha256, s"Component hash mismatch: ${component.name}")
    resolved
  }

  private def requireContainedDirectory(root: Path, path: Path, label: String): Unit = {
    val normalizedRoot = root.toAbsolutePath.normalize()
    val normalizedPath = path.toAbsolutePath.normalize()
    require(normalizedPath.startsWith(normalizedRoot), s"$label escapes its configured root")
    require(Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS), s"Missing $label: $normalizedPath")
    var current = normalizedPath
    while (current != null && current.startsWith(normalizedRoot)) {
      require(!Files.isSymbolicLink(current), s"Symbolic links are not allowed in $label: $current")
      if (current == normalizedRoot) current = null else current = current.getParent
    }
  }

  private def validateBundleId(value: String): Unit =
    require(BundleIdPattern.pattern.matcher(value).matches(), s"Invalid bundle ID: $value")

  private def readConfig(path: Path, label: String): Config = {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), s"Missing $label: $path")
    require(!Files.isSymbolicLink(path), s"Symbolic links are not allowed for $label: $path")
    ConfigFactory.parseFile(path.toFile).resolve()
  }

  private def requiredString(value: Config, path: String, label: String): String = {
    require(value.hasPath(path) && !value.getIsNull(path), s"Missing $path in $label")
    val result = value.getString(path)
    require(result.nonEmpty, s"Empty $path in $label")
    result
  }

  private def requiredHash(value: Config, path: String, label: String): String = {
    val result = requiredString(value, path, label)
    require(HashPattern.pattern.matcher(result).matches(), s"Invalid $path in $label")
    result
  }

  private[bundle] def directorySha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.walk(path)
    try stream.iterator().asScala.filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS)).toSeq
      .sortBy(_.toString).foreach { file =>
        digest.update(path.relativize(file).toString.getBytes(StandardCharsets.UTF_8))
        digest.update(fileSha256(file).getBytes(StandardCharsets.UTF_8))
      }
    finally stream.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  private[bundle] def fileSha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](1024 * 1024)
      Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(size =>
        if (size > 0) digest.update(buffer, 0, size))
    } finally in.close()
    digest.digest().map("%02x".format(_)).mkString
  }
}
