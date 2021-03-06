/**
 * @author  Aapo Kyrola <akyrola@cs.cmu.edu>
 * @version 1.0
 *
 * @section LICENSE
 *
 * Copyright [2014] [Aapo Kyrola / Carnegie Mellon University]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Publication to cite:  http://arxiv.org/abs/1403.0701
 */
package edu.cmu.graphchidb.storage

import edu.cmu.graphchi.preprocessing.VertexIdTranslate
import edu.cmu.graphchidb.{DatabaseIndexing, Util}
import java.io._
import java.nio.channels.FileChannel.MapMode
import java.nio.{ByteBuffer, MappedByteBuffer}
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.collection.mutable.ArrayBuffer
import sun.reflect.generics.reflectiveObjects.NotImplementedException

/** Vardata columns have two parts: one is long-column holding indices to the
  * var data payload, which is stored in a special log (TODO: garbage collection).
  */
class VarDataColumn(name: String,  filePrefix: String, indexing: DatabaseIndexing)   {
  val prefixFilename = filePrefix +  ".vardata_" + name + "_" + indexing.name

  var maxFileSize = 128 * 1024 * 1024 // 128 megs


  private val bufferSize = 100000
  private var bufferIndexStart = 0
  private var bufferCounter = new AtomicInteger()

  private val buffer = new ByteArrayOutputStream(bufferSize * 100) {
    def currentBuf = buf
  }
  private val bufferDataStream = new DataOutputStream(buffer)

  var initialized = false

  val lock = new ReentrantReadWriteLock()

  def partialFileName(id: Int) = prefixFilename + ".%d".format(id)

  case class PartialVarDataFile(id: Int,  dataBuffer: MappedByteBuffer)

  def initPartialData(id: Int) = {
    val dataFile = new File(partialFileName(id))
    if (!dataFile.exists()) dataFile.createNewFile()
    val dataFileChannel = new RandomAccessFile(dataFile, "r").getChannel
    val dataBuffer = dataFileChannel.map(MapMode.READ_ONLY, 0, dataFile.length())
    dataFileChannel.close()
    PartialVarDataFile(id, dataBuffer)
  }


  var currentBufferPartId = 0
  val partialDataFiles = new ArrayBuffer[PartialVarDataFile]()

  def init() {
    this.synchronized {
      if (!initialized) {

        val existing =  Stream.from(0) takeWhile (i => {
          val f = new File(partialFileName(i))
          f.exists()
        })
        partialDataFiles ++= existing.map(i => initPartialData(i))
        startNewPart()
        initialized = true
        println("Initialized %s, %d, new part id = %d".format(prefixFilename, partialDataFiles.size, currentBufferPartId))
      }
    }
  }



  init()

  def startNewPart(): Unit = {
    lock.writeLock().lock()
    try {
      val newId = if (partialDataFiles.isEmpty) { 0 } else {partialDataFiles.last.id + 1 }
      partialDataFiles += initPartialData(newId)
      currentBufferPartId = newId
    } finally {
      lock.writeLock.unlock()
    }
  }

  def flushBuffer() = {
    lock.writeLock().lock()
    try {
      val dataFile = new File(partialFileName(currentBufferPartId))
      val logOutput = new FileOutputStream(dataFile, true)
      logOutput.write(buffer.toByteArray)
      logOutput.close()

      partialDataFiles(partialDataFiles.size - 1) = initPartialData(partialDataFiles.last.id)

      bufferCounter.set(0)
      buffer.reset()

      if (dataFile.length > maxFileSize) {
        startNewPart()
        bufferIndexStart = 0
      } else {
        bufferIndexStart = partialDataFiles.last.dataBuffer.capacity()
      }

    } finally {
      lock.writeLock().unlock()
    }

  }

  def insert(str: String) : Long = insert(str.getBytes)

  def insert(data: Array[Byte]) : Long = {
    lock.writeLock().lock
    try {
      if (!initialized) throw new IllegalStateException("Not initialized")
      val id = bufferIndexStart + buffer.size()

      bufferDataStream.writeInt(data.length) // First store length word
      bufferDataStream.write(data)
      val bufPartId = currentBufferPartId

      if (bufferCounter.incrementAndGet() >= bufferSize) {
        flushBuffer
      }
      Util.setHiLo(bufPartId, id)
    } finally {
      lock.writeLock().unlock()
    }
  }

  def get(globalId: Long) : Array[Byte] = {
    val partialId = Util.hiBytes(globalId)
    val localIdx = Util.loBytes(globalId)
    var needLock = partialId == currentBufferPartId
    if (needLock) lock.readLock().lock()
    try {
      if (needLock && localIdx >= partialDataFiles(partialId).dataBuffer.capacity()) {
        // Look from buffers
        val bufferOff = localIdx - bufferIndexStart
        val lengthArray = new Array[Byte](4)
        Array.copy(buffer.currentBuf, bufferOff, lengthArray, 0, 4)
        val len = Util.intFromByteArray(lengthArray)

        val res = new Array[Byte](len.toInt)
        Array.copy(buffer.currentBuf, bufferOff + 4, res, 0, len)

        res
      } else {
        if (partialId >= partialDataFiles.size) {
          println("Accessing partial id %d, but size: %d, globalid=%s".format(partialId, partialDataFiles.size, globalId))
        }

        // Seek file
        val dataBuffer = partialDataFiles(partialId).dataBuffer
        val tmpBuffer = dataBuffer.duplicate()
        tmpBuffer.position(localIdx)
        val len = tmpBuffer.getInt
        val res = new Array[Byte](len)
        tmpBuffer.get(res)
        res
      }
    } finally {
      if (needLock)  lock.readLock().unlock()
    }
  }

  def getString(globalId: Long) = new String(get(globalId))

  def delete(id: Long) : Unit = {
    // Not implemented now
  }
}
