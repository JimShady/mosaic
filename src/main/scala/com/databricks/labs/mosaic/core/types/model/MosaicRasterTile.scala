package com.databricks.labs.mosaic.core.types.model

import com.databricks.labs.mosaic.core.index.IndexSystem
import com.databricks.labs.mosaic.core.raster.api.GDAL
import com.databricks.labs.mosaic.core.raster.gdal.MosaicRasterGDAL
import com.databricks.labs.mosaic.expressions.raster.{buildMapString, extractMap}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.{BinaryType, DataType, LongType, StringType}
import org.apache.spark.unsafe.types.UTF8String

import scala.util.{Failure, Success, Try}

/**
  * A case class modeling an instance of a mosaic raster tile.
  *
  * @param index
  *   Index ID.
  * @param raster
  *   Raster instance corresponding to the tile.
  */
case class MosaicRasterTile(
    index: Either[Long, String],
    raster: MosaicRasterGDAL
) {

    def parentPath: String = raster.createInfo("parentPath")

    def driver: String = raster.createInfo("driver")

    def getIndex: Either[Long, String] = index

    def getParentPath: String = parentPath

    def getDriver: String = driver

    def getRaster: MosaicRasterGDAL = raster

    /**
      * Indicates whether the raster is present.
      *
      * @return
      *   True if the raster is present, false otherwise.
      */
    def isEmpty: Boolean = Option(raster).forall(_.isEmpty)

    /**
      * Formats the index ID as the data type supplied by the index system.
      *
      * @param indexSystem
      *   Index system to use for formatting.
      * @return
      *   MosaicChip with formatted index ID.
      */
    def formatCellId(indexSystem: IndexSystem): MosaicRasterTile = {
        if (Option(index).isEmpty) return this
        (indexSystem.getCellIdDataType, index) match {
            case (_: LongType, Left(_))       => this
            case (_: StringType, Right(_))    => this
            case (_: LongType, Right(value))  => this.copy(index = Left(indexSystem.parse(value)))
            case (_: StringType, Left(value)) => this.copy(index = Right(indexSystem.format(value)))
            case _                            => throw new IllegalArgumentException("Invalid cell id data type")
        }
    }

    /**
      * Formats the index ID as the long type.
      *
      * @param indexSystem
      *   Index system to use for formatting.
      * @return
      *   MosaicChip with formatted index ID.
      */
    def cellIdAsLong(indexSystem: IndexSystem): Long =
        index match {
            case Left(value) => value
            case _           => indexSystem.parse(index.right.get)
        }

    /**
      * Formats the index ID as the string type.
      *
      * @param indexSystem
      *   Index system to use for formatting.
      * @return
      *   MosaicChip with formatted index ID.
      */
    def cellIdAsStr(indexSystem: IndexSystem): String =
        index match {
            case Right(value) => value
            case _            => indexSystem.format(index.left.get)
        }

    /**
      * Serialise to spark internal representation.
      *
      * @return
      *   An instance of [[InternalRow]].
      */
    def serialize(
        rasterDataType: DataType
    ): InternalRow = {
        val encodedRaster = encodeRaster(rasterDataType)
        val path = if (rasterDataType == StringType) encodedRaster.toString else raster.createInfo("path")
        val parentPath = if (raster.createInfo("parentPath").isEmpty) raster.createInfo("path") else raster.createInfo("parentPath")
        val newCreateInfo = raster.createInfo + ("path" -> path, "parentPath" -> parentPath)
        val mapData = buildMapString(newCreateInfo)
        if (Option(index).isDefined) {
            if (index.isLeft) InternalRow.fromSeq(
              Seq(index.left.get, encodedRaster, mapData)
            )
            else {
                InternalRow.fromSeq(
                  Seq(UTF8String.fromString(index.right.get), encodedRaster, mapData)
                )
            }
        } else {
            InternalRow.fromSeq(Seq(null, encodedRaster, mapData))
        }

    }

    /**
      * Encodes the chip geometry as WKB.
      *
      * @return
      *   An instance of [[Array]] of [[Byte]] representing WKB.
      */
    private def encodeRaster(
        rasterDataType: DataType = BinaryType
    ): Any = {
        GDAL.writeRasters(Seq(raster), rasterDataType).head
    }

    def getSequenceNumber: Int =
        Try(raster.getRaster.GetMetadataItem("BAND_INDEX", "DATABRICKS_MOSAIC")) match {
            case Success(value) => value.toInt
            case Failure(_)     => -1
        }

}

/** Companion object. */
object MosaicRasterTile {

    /**
      * Smart constructor based on Spark internal instance.
      *
      * @param row
      *   An instance of [[InternalRow]].
      * @param idDataType
      *   The data type of the index ID.
      * @return
      *   An instance of [[MosaicRasterTile]].
      */
    def deserialize(row: InternalRow, idDataType: DataType, rasterType: DataType): MosaicRasterTile = {
        val index = row.get(0, idDataType)
        val rawRaster = row.get(1, rasterType)
        val createInfo = extractMap(row.getMap(2))
        val raster = GDAL.readRaster(rawRaster, createInfo, rasterType)
        // noinspection TypeCheckCanBeMatch
        if (Option(index).isDefined) {
            if (index.isInstanceOf[Long]) {
                new MosaicRasterTile(Left(index.asInstanceOf[Long]), raster)
            } else {
                new MosaicRasterTile(Right(index.asInstanceOf[UTF8String].toString), raster)
            }
        } else {
            new MosaicRasterTile(null, raster)
        }

    }

}
