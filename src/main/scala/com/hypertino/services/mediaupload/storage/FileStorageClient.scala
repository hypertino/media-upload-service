package com.hypertino.services.mediaupload.storage
import java.io.InputStream
import java.nio.file._

class FileStorageClient(rootPath: String,
                        uploadOptions: CopyOption = StandardCopyOption.REPLACE_EXISTING,
                        downloadOptions: CopyOption = StandardCopyOption.REPLACE_EXISTING) extends StorageClient {

  override def upload(bucket: String, path: String, stream: InputStream, contentType: Option[String]): String = {
    val targetPath = Paths.get(rootPath, bucket, path)
    Files.copy(stream,targetPath,uploadOptions)
    targetPath.toString
  }

  override def download(bucket: String, path: String, localFileName: String): Unit = {
    val sourcePath = Paths.get(rootPath, bucket, path)
    Files.copy(sourcePath, Paths.get(localFileName), downloadOptions)
  }
}
