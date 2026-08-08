package atlas.release

import com.typesafe.config.ConfigFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant

final case class WorkManifest(
    release: String,
    createdAt: Instant,
    completedAt: Instant,
    producer: String,
    outputPath: String
)

object WorkManifest {
  val FileName = ".atlas-work-manifest.json"

  def write(releaseRoot: Path, manifest: WorkManifest): Path = {
    Files.createDirectories(releaseRoot)
    val target = releaseRoot.resolve(FileName)
    val body =
      s"""{
         |  "release": "${escape(manifest.release)}",
         |  "created_at": "${manifest.createdAt}",
         |  "completed_at": "${manifest.completedAt}",
         |  "producer": "${escape(manifest.producer)}",
         |  "output_path": "${escape(manifest.outputPath)}"
         |}
         |""".stripMargin
    Files.write(target, body.getBytes(StandardCharsets.UTF_8))
    target
  }

  def read(releaseRoot: Path): WorkManifest = {
    val config = ConfigFactory.parseFile(releaseRoot.resolve(FileName).toFile).resolve()
    WorkManifest(
      config.getString("release"),
      Instant.parse(config.getString("created_at")),
      Instant.parse(config.getString("completed_at")),
      config.getString("producer"),
      config.getString("output_path")
    )
  }

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
}
