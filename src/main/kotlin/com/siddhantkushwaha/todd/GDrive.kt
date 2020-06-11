package com.siddhantkushwaha.todd

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.min
import kotlin.math.pow


class GDrive {

    private val APPLICATION_NAME = "GDrive - Todd"
    private val SCOPES = listOf(DriveScopes.DRIVE)
    private val JSON_FACTORY: JsonFactory = JacksonFactory.getDefaultInstance()
    private val CREDENTIALS_FILE_PATH = "/credentials.json"
    private val TOKENS_DIRECTORY_PATH = "tokens"

    /* 32 MB */
    private val CHUNK_SIZE: Long = 32 * 2.0.pow(20).toLong()

    private val service: Drive

    init {
        val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
        service = Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential {
        // Load client secrets.

        val inputStream = GDrive::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)
            ?: throw FileNotFoundException("Resource not found: $CREDENTIALS_FILE_PATH")

        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(inputStream))

        // Build flow and trigger user authorization request.
        val flow = GoogleAuthorizationCodeFlow.Builder(
            HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES
        )
            .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
            .setAccessType("offline")
            .build()
        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }

    public fun getSize(fileId: String): Long {
        val file = service.files().get(fileId).setFields("size").execute()!!
        return file.getSize()
    }

    public fun getName(fileId: String): String {
        val file = service.files().get(fileId).setFields("name").execute()!!
        return file.name
    }

    public fun download(
        fileId: String,
        filePath: String? = null,
        firstBytePos: Long? = null,
        lastBytePos: Long? = null
    ): ByteArray? {
        val request = service.files().get(fileId)
        request.mediaHttpDownloader.isDirectDownloadEnabled = false
        request.mediaHttpDownloader.chunkSize = CHUNK_SIZE.toInt()

        if (firstBytePos != null && lastBytePos != null) {
            // **** Range headers not necessary for direct downloads *****
            // request.requestHeaders.range = "bytes=7-9"

            // this works
            request.mediaHttpDownloader.setContentRange(firstBytePos, lastBytePos.toInt())
        }

        val outputStream = if (filePath == null)
            ByteArrayOutputStream()
        else
            FileOutputStream(filePath)

        request.executeMediaAndDownloadTo(outputStream)

        return if (filePath == null) {
            (outputStream as ByteArrayOutputStream).toByteArray()
        } else
            null
    }

    public fun downloadLocally(fileId: String, downloadDir: String, numWorkers: Int = 8) {
        val fileSize = getSize(fileId)
        val fileName = getName(fileId)

        /* chunk size of 25 MB */
        val chunkSizeConst: Long = 25 * 2.0.pow(20).toLong()

        val chunkDir = Paths.get(downloadDir, fileId).toString()
        Files.createDirectories(Paths.get(chunkDir))

        val tasks = ArrayList<DownloadTask>()
        val chunks = ArrayList<String>()

        // loop on required chunks and create download tasks
        var firstBytePos: Long = 0
        while (firstBytePos < fileSize) {

            val lastBytePos: Long = min(firstBytePos + chunkSizeConst - 1, fileSize - 1)

            val chunkName = "chunk-$firstBytePos-$lastBytePos"
            val chunkPath = Paths.get(chunkDir, chunkName).toString()

            val oldChunk = File(chunkPath)
            val chunkSize = if (oldChunk.exists() && oldChunk.isFile)
                oldChunk.length()
            else {
                oldChunk.delete()
                0
            }
            val expectedChunkSize: Long = (lastBytePos - firstBytePos) + 1

            if (chunkSize != expectedChunkSize)
                tasks.add(
                    DownloadTask(
                        this,
                        fileId,
                        chunkPath,
                        firstBytePos,
                        lastBytePos
                    )
                )

            firstBytePos += expectedChunkSize
            chunks.add(chunkPath)
        }

        // actual downloading starts here for remaning chunks
        val executor = Executors.newFixedThreadPool(numWorkers)
        executor.invokeAll(tasks)
        executor.shutdown()

        val filePath = Paths.get(downloadDir, fileName).toString()
        val fileStream = FileOutputStream(filePath)
        chunks.forEach { chunkPath ->
            val inputStream = FileInputStream(chunkPath)
            fileStream.write(inputStream.readAllBytes())
        }

        if (File(filePath).length() == fileSize) {
            println("File downloaded, deleting chunks..")
            File(chunkDir).deleteRecursively()
        }

        println("Completed.")
    }

    public fun downloadAsInputStream(
        fileId: String,
        firstBytePos: Long? = null,
        lastBytePos: Long? = null
    ): InputStream {
        val request = service.files().get(fileId)
        request.mediaHttpDownloader.isDirectDownloadEnabled = false
        request.mediaHttpDownloader.chunkSize = CHUNK_SIZE.toInt()

        if (firstBytePos != null) {
            // **** Range headers are required here
            //this works
            request.requestHeaders.range = "bytes=$firstBytePos-${lastBytePos ?: 0}"

            // this does not work
            // request.mediaHttpDownloader.setContentRange(firstBytePos, lastBytePos.toInt())
        }

        return request.executeMediaAsInputStream()
    }

    private fun createDirectory(name: String, driveFolderParentId: String? = null): String {
        val fileMetadata = com.google.api.services.drive.model.File()
        fileMetadata.name = name
        if (driveFolderParentId != null)
            fileMetadata.parents = mutableListOf(driveFolderParentId)
        fileMetadata.mimeType = "application/vnd.google-apps.folder"
        val file = service.files().create(fileMetadata).setFields("id").execute()
        return file.id
    }

    private fun uploadDirectory(directoryPath: Path, driveFolderParentId: String? = null) {

        val cache = HashMap<String, String>()
        Files.walk(directoryPath).filter { it.toFile().isFile }.forEach { filePath: Path ->

            val relativeFilePath = directoryPath.parent.relativize(filePath)
            val parentPaths: Stack<Path> = Stack()
            var parentPath = relativeFilePath.parent
            while (parentPath != null) {
                parentPaths.add(parentPath)
                parentPath = parentPath.parent
            }

            var tempDriveFolderParentId = driveFolderParentId
            while (!parentPaths.empty()) {
                parentPath = parentPaths.peek()

                val name = parentPath.fileName.toString()
                val key = "$name-$tempDriveFolderParentId"

                if (cache.containsKey(key))
                    tempDriveFolderParentId = cache[key]
                else {
                    println("Creating directory: $parentPath")
                    tempDriveFolderParentId = createDirectory(name, tempDriveFolderParentId)
                    cache[key] = tempDriveFolderParentId
                }
                parentPaths.pop()
            }

            println("Uploading file: $filePath")
            uploadFile(filePath = filePath, driveFolderParentId = tempDriveFolderParentId)
        }
    }

    private fun uploadFile(filePath: Path, fileType: String = "", driveFolderParentId: String? = null) {
        val uploadFile = filePath.toFile()

        val mediaContent = FileContent(fileType, uploadFile)
        val fileMetadata = com.google.api.services.drive.model.File()
        fileMetadata.name = uploadFile.name

        if (!driveFolderParentId.isNullOrBlank()) {
            val parents = mutableListOf<String>()
            parents.add(driveFolderParentId)
            fileMetadata.parents = parents
        }

        val request = service.files().create(fileMetadata, mediaContent)
        request.mediaHttpUploader.isDirectUploadEnabled = false
        request.mediaHttpUploader.setProgressListener {
            println("Upload progress for ${uploadFile.name} - ${it.progress * 100}%")
        }
        request.execute()
    }

    public fun upload(path: Path, driveFolderParentId: String? = null) {
        val file = path.toFile()
        if (!file.exists())
            println("Path doesn't exist, aborting.")

        if (file.isDirectory)
            uploadDirectory(directoryPath = path.toRealPath(), driveFolderParentId = driveFolderParentId)
        else if (file.isFile)
            uploadFile(filePath = path.toRealPath(), driveFolderParentId = driveFolderParentId)
    }
}

