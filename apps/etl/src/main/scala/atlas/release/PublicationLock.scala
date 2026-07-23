package atlas.release

import atlas.config.AtlasConfig
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, StandardOpenOption}

object PublicationLock {
  def withEstablishmentsLock[A](config: AtlasConfig)(run: => A): A = {
    val paths = ReleasePaths(config)
    val lockPath = paths.atlasRoot.resolve("locks/receita-estabelecimentos-current.lock")
    Files.createDirectories(lockPath.getParent)
    val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    var lock: FileLock = null
    try {
      lock = try channel.tryLock()
      catch { case _: OverlappingFileLockException => null }
      if (lock == null)
        throw new IllegalStateException(s"Another establishment refresh or rebuild holds $lockPath")
      run
    } finally {
      if (lock != null && lock.isValid) lock.release()
      channel.close()
    }
  }

  def withCompanyBundleLock[A](config: AtlasConfig)(run: => A): A = {
    val lockPath = ReleasePaths(config).atlasRoot.resolve("locks/receita-company-bundle.lock")
    Files.createDirectories(lockPath.getParent)
    val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    var lock: FileLock = null
    try {
      lock = try channel.tryLock() catch { case _: OverlappingFileLockException => null }
      if (lock == null) throw new IllegalStateException(s"Another company-data refresh or rebuild holds $lockPath")
      run
    } finally {
      if (lock != null && lock.isValid) lock.release()
      channel.close()
    }
  }
}
