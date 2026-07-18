package atlas.release

import com.typesafe.config.ConfigFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.JavaConverters._

final case class TrashManifest(
    operationType: String,
    createdAt: Instant,
    originalPaths: Seq[String],
    replacementPaths: Seq[String],
    labels: Seq[String]
)

object TrashManifest {
  val FileName = ".atlas-trash-manifest.json"

  def write(root: Path, manifest: TrashManifest): Path = {
    Files.createDirectories(root)
    val target = root.resolve(FileName)
    val json = Seq(
      "operation_type" -> quoted(manifest.operationType),
      "created_at" -> quoted(manifest.createdAt.toString),
      "original_paths" -> array(manifest.originalPaths),
      "replacement_paths" -> array(manifest.replacementPaths),
      "labels" -> array(manifest.labels)
    ).map { case (key, value) => s"  ${quoted(key)}: $value" }.mkString("{\n", ",\n", "\n}\n")
    Files.write(target, json.getBytes(StandardCharsets.UTF_8))
    target
  }

  def read(root: Path): TrashManifest = {
    val config = ConfigFactory.parseFile(root.resolve(FileName).toFile).resolve()
    TrashManifest(
      config.getString("operation_type"),
      Instant.parse(config.getString("created_at")),
      config.getStringList("original_paths").asScala.toSeq,
      config.getStringList("replacement_paths").asScala.toSeq,
      config.getStringList("labels").asScala.toSeq
    )
  }

  private def array(values: Seq[String]): String = values.map(quoted).mkString("[", ", ", "]")
  private def quoted(value: String): String = "\"" + value.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c => c.toString
  } + "\""
}
