/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.io

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.channels.{Channels, WritableByteChannel}
import com.spotify.scio.ScioContext
import com.spotify.scio.io.BinaryIO.BytesSink
import com.spotify.scio.util.ScioUtil
import com.spotify.scio.util.FilenamePolicySupplier
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.io._
import org.apache.beam.sdk.io.fs.MatchResult.Metadata
import org.apache.beam.sdk.io.fs.ResourceId
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider
import org.apache.beam.sdk.transforms.SerializableFunctions
import org.apache.beam.sdk.util.MimeTypes
import org.apache.commons.compress.compressors.CompressorStreamFactory

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * A ScioIO class for writing raw bytes to files. Like TextIO, but without newline delimiters and
 * operating over Array[Byte] instead of String.
 * @param path
 *   a path to write to.
 */
final case class BinaryIO(path: String) extends ScioIO[Array[Byte]] {
  override type ReadP = Nothing
  override type WriteP = BinaryIO.WriteParam
  override val tapT: TapT.Aux[Array[Byte], Nothing] = EmptyTapOf[Array[Byte]]

  override def testId: String = s"BinaryIO($path)"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[Array[Byte]] =
    throw new UnsupportedOperationException("BinaryIO is write-only")

  private def binaryOut(
    path: String,
    prefix: String,
    suffix: String,
    numShards: Int,
    compression: Compression,
    header: Array[Byte],
    footer: Array[Byte],
    shardNameTemplate: String,
    framePrefix: Array[Byte] => Array[Byte],
    frameSuffix: Array[Byte] => Array[Byte],
    tempDirectory: ResourceId,
    filenamePolicySupplier: FilenamePolicySupplier,
    isWindowed: Boolean
  ): WriteFiles[Array[Byte], Void, Array[Byte]] = {
    val fp = FilenamePolicySupplier.resolve(
      path,
      suffix,
      shardNameTemplate,
      tempDirectory,
      filenamePolicySupplier,
      isWindowed,
      prefix
    )
    val dynamicDestinations =
      DynamicFileDestinations.constant(fp, SerializableFunctions.identity[Array[Byte]])
    val sink = new BytesSink(
      header,
      footer,
      framePrefix,
      frameSuffix,
      tempDirectory,
      dynamicDestinations,
      compression
    )
    val transform = WriteFiles.to(sink).withNumShards(numShards)
    if (!isWindowed) transform else transform.withWindowedWrites()
  }

  override protected def write(data: SCollection[Array[Byte]], params: WriteP): Tap[Nothing] = {
    data.applyInternal(
      binaryOut(
        path,
        params.prefix,
        params.suffix,
        params.numShards,
        params.compression,
        params.header,
        params.footer,
        params.shardNameTemplate,
        params.framePrefix,
        params.frameSuffix,
        ScioUtil.tempDirOrDefault(params.tempDirectory, data.context),
        params.filenamePolicySupplier,
        ScioUtil.isWindowed(data)
      )
    )
    EmptyTap
  }

  override def tap(params: Nothing): Tap[Nothing] = EmptyTap
}

object BinaryIO {

  private[scio] def openInputStreamsFor(path: String): Iterator[InputStream] = {
    val factory = new CompressorStreamFactory()

    def wrapInputStream(in: InputStream) = {
      val buffered = new BufferedInputStream(in)
      Try(factory.createCompressorInputStream(buffered)).getOrElse(buffered)
    }

    listFiles(path).map(getObjectInputStream).map(wrapInputStream).iterator
  }

  private def listFiles(path: String): Seq[Metadata] =
    FileSystems.`match`(path).metadata().iterator.asScala.toSeq

  private def getObjectInputStream(meta: Metadata): InputStream =
    Channels.newInputStream(FileSystems.open(meta.resourceId()))

  object WriteParam {
    private[scio] val DefaultPrefix = null
    private[scio] val DefaultSuffix = ".bin"
    private[scio] val DefaultNumShards = 0
    private[scio] val DefaultCompression = Compression.UNCOMPRESSED
    private[scio] val DefaultHeader = Array.emptyByteArray
    private[scio] val DefaultFooter = Array.emptyByteArray
    private[scio] val DefaultShardNameTemplate: String = null
    private[scio] val DefaultFramePrefix: Array[Byte] => Array[Byte] = _ => Array.emptyByteArray
    private[scio] val DefaultFrameSuffix: Array[Byte] => Array[Byte] = _ => Array.emptyByteArray
    private[scio] val DefaultTempDirectory = null
    private[scio] val DefaultFilenamePolicySupplier = null
  }

  final case class WriteParam(
    prefix: String = WriteParam.DefaultPrefix,
    suffix: String = WriteParam.DefaultSuffix,
    numShards: Int = WriteParam.DefaultNumShards,
    compression: Compression = WriteParam.DefaultCompression,
    header: Array[Byte] = WriteParam.DefaultHeader,
    footer: Array[Byte] = WriteParam.DefaultFooter,
    shardNameTemplate: String = WriteParam.DefaultShardNameTemplate,
    framePrefix: Array[Byte] => Array[Byte] = WriteParam.DefaultFramePrefix,
    frameSuffix: Array[Byte] => Array[Byte] = WriteParam.DefaultFrameSuffix,
    tempDirectory: String = WriteParam.DefaultTempDirectory,
    filenamePolicySupplier: FilenamePolicySupplier = WriteParam.DefaultFilenamePolicySupplier
  )

  final private class BytesSink(
    val header: Array[Byte],
    val footer: Array[Byte],
    val framePrefix: Array[Byte] => Array[Byte],
    val frameSuffix: Array[Byte] => Array[Byte],
    val tempDirectory: ResourceId,
    val dynamicDestinations: FileBasedSink.DynamicDestinations[Array[Byte], Void, Array[Byte]],
    val compression: Compression
  ) extends FileBasedSink[Array[Byte], Void, Array[Byte]](
        StaticValueProvider.of(tempDirectory),
        dynamicDestinations,
        compression
      ) {
    override def createWriteOperation(): FileBasedSink.WriteOperation[Void, Array[Byte]] = {
      new FileBasedSink.WriteOperation[Void, Array[Byte]](this) {
        override def createWriter(): FileBasedSink.Writer[Void, Array[Byte]] = {
          new FileBasedSink.Writer[Void, Array[Byte]](this, MimeTypes.BINARY) {
            @transient private var channel: OutputStream = _
            override def prepareWrite(channel: WritableByteChannel): Unit = this.channel =
              Channels.newOutputStream(channel)
            override def writeHeader(): Unit = this.channel.write(header)
            override def writeFooter(): Unit = this.channel.write(footer)

            override def write(value: Array[Byte]): Unit = {
              if (this.channel == null) {
                throw new IllegalStateException(
                  "Trying to write to a BytesSink that has not been opened"
                )
              }
              this.channel.write(framePrefix(value))
              this.channel.write(value)
              this.channel.write(frameSuffix(value))
            }

            override def finishWrite(): Unit = {
              if (this.channel == null) {
                throw new IllegalStateException(
                  "Trying to flush a BytesSink that has not been opened"
                )
              }
              this.channel.flush()
            }
          }
        }
      }
    }
  }
}
