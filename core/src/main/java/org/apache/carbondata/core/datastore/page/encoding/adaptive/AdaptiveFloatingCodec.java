/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.core.datastore.page.encoding.adaptive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.apache.carbondata.core.datastore.compression.Compressor;
import org.apache.carbondata.core.datastore.compression.CompressorFactory;
import org.apache.carbondata.core.datastore.page.ColumnPage;
import org.apache.carbondata.core.datastore.page.ColumnPageValueConverter;
import org.apache.carbondata.core.datastore.page.DecimalColumnPage;
import org.apache.carbondata.core.datastore.page.LazyColumnPage;
import org.apache.carbondata.core.datastore.page.encoding.ColumnPageDecoder;
import org.apache.carbondata.core.datastore.page.encoding.ColumnPageEncoder;
import org.apache.carbondata.core.datastore.page.encoding.ColumnPageEncoderMeta;
import org.apache.carbondata.core.datastore.page.statistics.SimpleStatsResult;
import org.apache.carbondata.core.memory.MemoryException;
import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.scan.result.vector.CarbonColumnVector;
import org.apache.carbondata.core.scan.result.vector.ColumnVectorInfo;
import org.apache.carbondata.core.scan.result.vector.impl.directread.ColumnarVectorWrapperDirectFactory;
import org.apache.carbondata.core.util.ByteUtil;
import org.apache.carbondata.format.DataChunk2;
import org.apache.carbondata.format.Encoding;

/**
 * Codec for floating point (float, double) data type page.
 * This codec will upscale the diff from page max value to integer value,
 * and do type casting to make storage minimum.
 */
public class AdaptiveFloatingCodec extends AdaptiveCodec {

  private double factor;
  private float floatFactor;

  public AdaptiveFloatingCodec(DataType srcDataType, DataType targetDataType,
      SimpleStatsResult stats, boolean isInvertedIndex) {
    super(srcDataType, targetDataType, stats, isInvertedIndex);
    this.factor = Math.pow(10, stats.getDecimalCount());
    this.floatFactor = (float) factor;
  }

  @Override
  public String getName() {
    return "AdaptiveFloatingCodec";
  }

  @Override
  public ColumnPageEncoder createEncoder(Map<String, String> parameter) {
    return new ColumnPageEncoder() {
      byte[] result = null;
      @Override
      protected byte[] encodeData(ColumnPage input) throws MemoryException, IOException {
        if (encodedPage != null) {
          throw new IllegalStateException("already encoded");
        }
        Compressor compressor =
            CompressorFactory.getInstance().getCompressor(input.getColumnCompressorName());
        result = encodeAndCompressPage(input, converter, compressor);
        byte[] bytes = writeInvertedIndexIfRequired(result);
        encodedPage.freeMemory();
        if (bytes.length != 0) {
          return bytes;
        }
        return result;
      }

      @Override
      protected List<Encoding> getEncodingList() {
        List<Encoding> encodings = new ArrayList<Encoding>();
        encodings.add(Encoding.ADAPTIVE_FLOATING);
        if (null != indexStorage && indexStorage.getRowIdPageLengthInBytes() > 0) {
          encodings.add(Encoding.INVERTED_INDEX);
        }
        return encodings;
      }

      @Override
      protected ColumnPageEncoderMeta getEncoderMeta(ColumnPage inputPage) {
        return new ColumnPageEncoderMeta(inputPage.getColumnSpec(), targetDataType, stats,
            inputPage.getColumnCompressorName());
      }

      @Override
      protected void fillLegacyFields(DataChunk2 dataChunk) throws IOException {
        fillLegacyFieldsIfRequired(dataChunk, result);
      }

    };
  }

  @Override
  public ColumnPageDecoder createDecoder(final ColumnPageEncoderMeta meta) {
    return new ColumnPageDecoder() {
      @Override
      public ColumnPage decode(byte[] input, int offset, int length)
          throws MemoryException, IOException {
        ColumnPage page = ColumnPage.decompress(meta, input, offset, length, false);
        return LazyColumnPage.newPage(page, converter);
      }

      @Override
      public void decodeAndFillVector(byte[] input, int offset, int length,
          ColumnVectorInfo vectorInfo, BitSet nullBits, boolean isLVEncoded)
          throws MemoryException, IOException {
        ColumnPage page = ColumnPage.decompress(meta, input, offset, length, isLVEncoded);
        page.setNullBits(nullBits);
        if (page instanceof DecimalColumnPage) {
          vectorInfo.decimalConverter = ((DecimalColumnPage) page).getDecimalConverter();
        }
        converter.decodeAndFillVector(page, vectorInfo);
      }

      @Override
      public ColumnPage decode(byte[] input, int offset, int length, boolean isLVEncoded)
          throws MemoryException, IOException {
        return decode(input, offset, length);
      }
    };
  }

  // encoded value = (10 power of decimal) * (page value)
  private ColumnPageValueConverter converter = new ColumnPageValueConverter() {
    @Override
    public void encode(int rowId, byte value) {
      // this codec is for floating point type only
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public void encode(int rowId, short value) {
      // this codec is for floating point type only
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public void encode(int rowId, int value) {
      // this codec is for floating point type only
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public void encode(int rowId, long value) {
      // this codec is for floating point type only
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public void encode(int rowId, float value) {
      encode(rowId, (double) value);
    }

    @Override
    public void encode(int rowId, double value) {
      if (targetDataType == DataTypes.BYTE) {
        encodedPage.putByte(rowId, (byte) Math.round(value * factor));
      } else if (targetDataType == DataTypes.SHORT) {
        encodedPage.putShort(rowId, (short) Math.round(value * factor));
      } else if (targetDataType == DataTypes.SHORT_INT) {
        encodedPage.putShortInt(rowId, (int) Math.round(value * factor));
      } else if (targetDataType == DataTypes.INT) {
        encodedPage.putInt(rowId, (int) Math.round(value * factor));
      } else if (targetDataType == DataTypes.LONG) {
        encodedPage.putLong(rowId, (long) Math.round(value * factor));
      } else if (targetDataType == DataTypes.DOUBLE) {
        encodedPage.putDouble(rowId, value);
      } else {
        throw new RuntimeException("internal error: " + debugInfo());
      }
    }

    @Override
    public long decodeLong(byte value) {
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public long decodeLong(short value) {
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public long decodeLong(int value) {
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public double decodeDouble(byte value) {
      return value / factor;
    }

    @Override
    public double decodeDouble(short value) {
      return value / factor;
    }

    @Override
    public double decodeDouble(int value) {
      return value / factor;
    }

    @Override
    public double decodeDouble(long value) {
      return value / factor;
    }

    @Override
    public double decodeDouble(float value) {
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public double decodeDouble(double value) {
      throw new RuntimeException("internal error: " + debugInfo());
    }

    @Override
    public void decodeAndFillVector(ColumnPage columnPage, ColumnVectorInfo vectorInfo) {
      CarbonColumnVector vector = vectorInfo.vector;
      BitSet nullBits = columnPage.getNullBits();
      DataType pageDataType = columnPage.getDataType();
      int pageSize = columnPage.getPageSize();
      BitSet deletedRows = vectorInfo.deletedRows;
      DataType vectorDataType = vector.getType();
      vector = ColumnarVectorWrapperDirectFactory
          .getDirectVectorWrapperFactory(vector, null, nullBits, deletedRows, true, false);
      if (vectorDataType == DataTypes.FLOAT) {
        if (pageDataType == DataTypes.BOOLEAN || pageDataType == DataTypes.BYTE) {
          byte[] byteData = columnPage.getBytePage();
          for (int i = 0; i < pageSize; i++) {
            vector.putFloat(i, (byteData[i] / floatFactor));
          }
        } else if (pageDataType == DataTypes.SHORT) {
          short[] shortData = columnPage.getShortPage();
          for (int i = 0; i < pageSize; i++) {
            vector.putFloat(i, (shortData[i] / floatFactor));
          }

        } else if (pageDataType == DataTypes.SHORT_INT) {
          byte[] shortIntPage = columnPage.getShortIntPage();
          for (int i = 0; i < pageSize; i++) {
            int shortInt = ByteUtil.valueOf3Bytes(shortIntPage, i * 3);
            vector.putFloat(i, (shortInt / floatFactor));
          }
        } else if (pageDataType == DataTypes.INT) {
          int[] intData = columnPage.getIntPage();
          for (int i = 0; i < pageSize; i++) {
            vector.putFloat(i, (intData[i] / floatFactor));
          }
        } else {
          throw new RuntimeException("internal error: " + this.toString());
        }
      } else {
        if (pageDataType == DataTypes.BOOLEAN || pageDataType == DataTypes.BYTE) {
          byte[] byteData = columnPage.getBytePage();
          for (int i = 0; i < pageSize; i++) {
            vector.putDouble(i, (byteData[i] / factor));
          }
        } else if (pageDataType == DataTypes.SHORT) {
          short[] shortData = columnPage.getShortPage();
          for (int i = 0; i < pageSize; i++) {
            vector.putDouble(i, (shortData[i] / factor));
          }

        } else if (pageDataType == DataTypes.SHORT_INT) {
          byte[] shortIntPage = columnPage.getShortIntPage();
          for (int i = 0; i < pageSize; i++) {
            int shortInt = ByteUtil.valueOf3Bytes(shortIntPage, i * 3);
            vector.putDouble(i, (shortInt / factor));
          }
        } else if (pageDataType == DataTypes.INT) {
          int[] intData = columnPage.getIntPage();
          for (int i = 0; i < pageSize; i++) {
            vector.putDouble(i, (intData[i] / factor));
          }
        } else if (pageDataType == DataTypes.LONG) {
          long[] longData = columnPage.getLongPage();
          for (int i = 0; i < pageSize; i++) {
            vector.putDouble(i, (longData[i] / factor));
          }
        } else {
          throw new RuntimeException("Unsupported datatype : " + pageDataType);
        }
      }

      if (deletedRows == null || deletedRows.isEmpty()) {
        for (int i = nullBits.nextSetBit(0); i >= 0; i = nullBits.nextSetBit(i + 1)) {
          vector.putNull(i);
        }
      }
    }
  };
}