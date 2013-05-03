/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions;

import junit.framework.Assert;

import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.gen.FilterStringColEqualStringScalar;
import org.apache.hadoop.hive.ql.exec.vector.expressions.gen.FilterStringColLessStringScalar;
import org.apache.hadoop.hive.ql.exec.vector.expressions.gen.
  FilterStringColGreaterEqualStringScalar;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import org.apache.hadoop.io.Text;

/**
 * Test vectorized expression and filter evaluation for strings.
 */
public class TestVectorStringExpressions {

  private static byte[] red;
  private static byte[] redred;
  private static byte[] red2; // second copy of red, different object
  private static byte[] green;
  private static byte[] greenred;
  private static byte[] redgreen;
  private static byte[] greengreen;
  private static byte[] emptyString;
  private static byte[] mixedUp;
  private static byte[] mixedUpLower;
  private static byte[] mixedUpUpper;
  private static byte[] multiByte;
  private static byte[] mixPercentPattern;

  static {
    try {
      red = "red".getBytes("UTF-8");
      redred = "redred".getBytes("UTF-8");
      green = "green".getBytes("UTF-8");
      greenred = "greenred".getBytes("UTF-8");
      redgreen = "redgreen".getBytes("UTF-8");
      greengreen = "greengreen".getBytes("UTF-8");
      emptyString = "".getBytes("UTF-8");
      mixedUp = "mixedUp".getBytes("UTF-8");
      mixedUpLower = "mixedup".getBytes("UTF-8");
      mixedUpUpper = "MIXEDUP".getBytes("UTF-8");
      mixPercentPattern = "mix%".getBytes("UTF-8"); // for use as wildcard pattern to test LIKE
      multiByte = new byte[100];
      addMultiByteChars(multiByte);
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    red2 = new byte[red.length];
    System.arraycopy(red, 0, red2, 0, red.length);
  }

  // add some multi-byte characters to test length routine later.
  // total characters = 4; byte length = 10
  static void addMultiByteChars(byte[] b) {
    int i = 0;
    b[i++] = (byte) 0x41; // letter "A" (1 byte)
    b[i++] = (byte) 0xC3; // Latin capital A with grave (2 bytes)
    b[i++] = (byte) 0x80;
    b[i++] = (byte) 0xE2; // Euro sign (3 bytes)
    b[i++] = (byte) 0x82;
    b[i++] = (byte) 0xAC;
    b[i++] = (byte) 0xF0; // Asian character U+24B62 (4 bytes)
    b[i++] = (byte) 0xA4;
    b[i++] = (byte) 0xAD;
    b[i++] = (byte) 0xA2;
  }

  @Test
  // Load a BytesColumnVector by copying in large data, enough to force
  // the buffer to expand.
  public void testLoadBytesColumnVectorByValueLargeData()  {
    BytesColumnVector bcv = new BytesColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    bcv.initBuffer(10); // initialize with estimated element size 10
    String s = "0123456789";
    while (s.length() < 500) {
      s += s;
    }
    byte[] b = null;
    try {
      b = s.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    for (int i = 0; i != VectorizedRowBatch.DEFAULT_SIZE; i++) {
      bcv.setVal(i, b, 0, b.length);
    }
    Assert.assertTrue(bcv.bufferSize() >= b.length * VectorizedRowBatch.DEFAULT_SIZE);
  }

  @Test
  // set values by reference, copy the data out, and verify equality
  public void testLoadBytesColumnVectorByRef() {
    BytesColumnVector bcv = new BytesColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    String s = "red";
    byte[] b = null;
    try {
      b = s.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    for (int i = 0; i != VectorizedRowBatch.DEFAULT_SIZE; i++) {
      bcv.setRef(i, b, 0, b.length);
    }
    // verify
    byte[] v = new byte[b.length];
    for (int i = 0; i != VectorizedRowBatch.DEFAULT_SIZE; i++) {
      Assert.assertTrue(bcv.length[i] == b.length);
      System.arraycopy(bcv.vector[i], bcv.start[i], v, 0, b.length);
      Assert.assertTrue(Arrays.equals(b, v));
    }
  }

  @Test
  // Test string column to string literal comparison
  public void testStringColCompareStringScalarFilter() {
    VectorizedRowBatch batch = makeStringBatch();
    VectorExpression expr;
    expr = new FilterStringColEqualStringScalar(0, red2);
    expr.evaluate(batch);

    // only red qualifies, and it's in entry 0
    Assert.assertTrue(batch.size == 1);
    Assert.assertTrue(batch.selected[0] == 0);

    batch = makeStringBatch();
    expr = new FilterStringColLessStringScalar(0, red2);
    expr.evaluate(batch);

    // only green qualifies, and it's in entry 1
    Assert.assertTrue(batch.size == 1);
    Assert.assertTrue(batch.selected[0] == 1);

    batch = makeStringBatch();
    expr = new FilterStringColGreaterEqualStringScalar(0, green);
    expr.evaluate(batch);

    // green and red qualify
    Assert.assertTrue(batch.size == 2);
    Assert.assertTrue(batch.selected[0] == 0);
    Assert.assertTrue(batch.selected[1] == 1);
  }

  VectorizedRowBatch makeStringBatch() {
    // create a batch with one string ("Bytes") column
    VectorizedRowBatch batch = new VectorizedRowBatch(2);
    BytesColumnVector v = new BytesColumnVector();
    batch.cols[0] = v;
    batch.cols[1] = new BytesColumnVector();          // to hold output if needed
    /*
     * Add these 3 values:
     *
     * red
     * green
     * NULL
     */
    v.setRef(0, red, 0, red.length);
    v.isNull[0] = false;
    v.setRef(1, green, 0, green.length);
    v.isNull[1] = false;
    v.setRef(2,  emptyString,  0,  emptyString.length);
    v.isNull[2] = true;

    v.noNulls = false;

    batch.size = 3;
    return batch;
  }

  VectorizedRowBatch makeStringBatchMixedCase() {
    // create a batch with two string ("Bytes") columns
    VectorizedRowBatch batch = new VectorizedRowBatch(2, VectorizedRowBatch.DEFAULT_SIZE);
    BytesColumnVector v = new BytesColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    batch.cols[0] = v;
    BytesColumnVector outV = new BytesColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    batch.cols[1] = outV;
    /*
     * Add these 3 values:
     *
     * mixedUp
     * green
     * NULL
     */
    v.setRef(0, mixedUp, 0, mixedUp.length);
    v.isNull[0] = false;
    v.setRef(1, green, 0, green.length);
    v.isNull[1] = false;
    v.setRef(2,  emptyString,  0,  emptyString.length);
    v.isNull[2] = true;
    v.noNulls = false;

    batch.size = 3;
    return batch;
  }

  VectorizedRowBatch makeStringBatchMixedCharSize() {

    // create a new batch with one char column (for input) and one long column (for output)
    VectorizedRowBatch batch = new VectorizedRowBatch(2, VectorizedRowBatch.DEFAULT_SIZE);
    BytesColumnVector v = new BytesColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    batch.cols[0] = v;
    LongColumnVector outV = new LongColumnVector(VectorizedRowBatch.DEFAULT_SIZE);
    batch.cols[1] = outV;

    /*
     * Add these 3 values:
     *
     * mixedUp
     * green
     * NULL
     * <4 char string with mult-byte chars>
     */
    v.setRef(0, mixedUp, 0, mixedUp.length);
    v.isNull[0] = false;
    v.setRef(1, green, 0, green.length);
    v.isNull[1] = false;
    v.setRef(2,  emptyString,  0,  emptyString.length);
    v.isNull[2] = true;
    v.noNulls = false;
    v.setRef(3, multiByte, 0, 10);
    v.isNull[3] = false;

    batch.size = 4;
    return batch;
  }

  @Test
  public void testColLower() {
    // has nulls, not repeating
    VectorizedRowBatch batch = makeStringBatchMixedCase();
    StringLower expr = new StringLower(0, 1);
    expr.evaluate(batch);
    BytesColumnVector outCol = (BytesColumnVector) batch.cols[1];
    int cmp = StringExpr.compare(mixedUpLower, 0, mixedUpLower.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isNull[2]);
    int cmp2 = StringExpr.compare(green, 0, green.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);

    // no nulls, not repeating
    batch = makeStringBatchMixedCase();
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(mixedUpLower, 0, mixedUpLower.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.noNulls);

    // has nulls, is repeating
    batch = makeStringBatchMixedCase();
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(mixedUpLower, 0, mixedUpLower.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertFalse(outCol.noNulls);

    // no nulls, is repeating
    batch = makeStringBatchMixedCase();
    batch.cols[0].isRepeating = true;
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(mixedUpLower, 0, mixedUpLower.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertTrue(outCol.noNulls);
  }

  @Test
  public void testColUpper() {

    // no nulls, not repeating

    /* We don't test all the combinations because (at least currently)
     * the logic is inherited to be the same as testColLower, which checks all the cases).
     */
    VectorizedRowBatch batch = makeStringBatchMixedCase();
    StringUpper expr = new StringUpper(0, 1);
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    BytesColumnVector outCol = (BytesColumnVector) batch.cols[1];
    int cmp = StringExpr.compare(mixedUpUpper, 0, mixedUpUpper.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.noNulls);
  }

  @Test
  public void testStringLength() {

    // has nulls, not repeating
    VectorizedRowBatch batch = makeStringBatchMixedCharSize();
    StringLength expr = new StringLength(0, 1);
    expr.evaluate(batch);
    LongColumnVector outCol = (LongColumnVector) batch.cols[1];
    Assert.assertEquals(5, outCol.vector[1]); // length of green is 5
    Assert.assertTrue(outCol.isNull[2]);
    Assert.assertEquals(4, outCol.vector[3]); // this one has the mixed-size chars

    // no nulls, not repeating
    batch = makeStringBatchMixedCharSize();
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (LongColumnVector) batch.cols[1];
    Assert.assertTrue(outCol.noNulls);
    Assert.assertEquals(4, outCol.vector[3]); // this one has the mixed-size chars

    // has nulls, is repeating
    batch = makeStringBatchMixedCharSize();
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);
    outCol = (LongColumnVector) batch.cols[1];
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertFalse(outCol.noNulls);
    Assert.assertEquals(7, outCol.vector[0]); // length of "mixedUp"

    // no nulls, is repeating
    batch = makeStringBatchMixedCharSize();
    batch.cols[0].isRepeating = true;
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (LongColumnVector) batch.cols[1];
    Assert.assertEquals(7, outCol.vector[0]); // length of "mixedUp"
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertTrue(outCol.noNulls);
  }

  private VectorizedRowBatch makeStringBatch2In1Out() {
    VectorizedRowBatch batch = new VectorizedRowBatch(3);
    BytesColumnVector v = new BytesColumnVector();
    batch.cols[0] = v;
    BytesColumnVector v2 = new BytesColumnVector();
    batch.cols[1] = v2;
    batch.cols[2] = new BytesColumnVector();

    v.setRef(0, red, 0, red.length);
    v.isNull[0] = false;
    v.setRef(1, green, 0, green.length);
    v.isNull[1] = false;
    v.setRef(2,  emptyString,  0,  emptyString.length);
    v.isNull[2] = true;
    v.noNulls = false;

    v2.setRef(0, red, 0, red.length);
    v2.isNull[0] = false;
    v2.setRef(1, green, 0, green.length);
    v2.isNull[1] = false;
    v2.setRef(2,  emptyString,  0,  emptyString.length);
    v2.isNull[2] = true;
    v2.noNulls = false;

    batch.size = 3;
    return batch;
  }

  @Test
  public void testStringLike() {

    // has nulls, not repeating
    VectorizedRowBatch batch;
    Text pattern;
    int initialBatchSize;
    batch = makeStringBatchMixedCharSize();
    pattern = new Text(mixPercentPattern);
    FilterStringColLikeStringScalar expr = new FilterStringColLikeStringScalar(0, pattern);
    expr.evaluate(batch);

    // verify that the beginning entry is the only one that matches
    Assert.assertEquals(1, batch.size);
    Assert.assertEquals(0, batch.selected[0]);

    // no nulls, not repeating
    batch = makeStringBatchMixedCharSize();
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);

    // verify that the beginning entry is the only one that matches
    Assert.assertEquals(1, batch.size);
    Assert.assertEquals(0, batch.selected[0]);

    // has nulls, is repeating
    batch = makeStringBatchMixedCharSize();
    initialBatchSize = batch.size;
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);

    // all rows qualify
    Assert.assertEquals(initialBatchSize, batch.size);

    // same, but repeating value is null
    batch = makeStringBatchMixedCharSize();
    batch.cols[0].isRepeating = true;
    batch.cols[0].isNull[0] = true;
    expr.evaluate(batch);

    // no rows qualify
    Assert.assertEquals(0, batch.size);

    // no nulls, is repeating
    batch = makeStringBatchMixedCharSize();
    initialBatchSize = batch.size;
    batch.cols[0].isRepeating = true;
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);

    // all rows qualify
    Assert.assertEquals(initialBatchSize, batch.size);
  }

  @Test
  public void testColConcatScalar() {

    // has nulls, not repeating
    VectorizedRowBatch batch = makeStringBatch();
    StringConcatColScalar expr = new StringConcatColScalar(0, 1, red);
    expr.evaluate(batch);
    BytesColumnVector outCol = (BytesColumnVector) batch.cols[1];

    int cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isNull[2]);
    int cmp2 = StringExpr.compare(greenred, 0, greenred.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);
    Assert.assertFalse(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // no nulls, not repeating
    batch = makeStringBatch();
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);

    cmp2 = StringExpr.compare(greenred, 0, greenred.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);

    int cmp3 = StringExpr.compare(red, 0, red.length, outCol.vector[2],
        outCol.start[2], outCol.length[2]);
    Assert.assertEquals(0, cmp3);

    Assert.assertTrue(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // has nulls, is repeating
    batch = makeStringBatch();
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertFalse(outCol.noNulls);

    // no nulls, is repeating
    batch = makeStringBatch();
    batch.cols[0].isRepeating = true;
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertTrue(outCol.noNulls);
  }

  @Test
  public void testScalarConcatCol() {

    // has nulls, not repeating
    VectorizedRowBatch batch = makeStringBatch();
    StringConcatScalarCol expr = new StringConcatScalarCol(red, 0, 1);
    expr.evaluate(batch);
    BytesColumnVector outCol = (BytesColumnVector) batch.cols[1];

    int cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isNull[2]);
    int cmp2 = StringExpr.compare(redgreen, 0, redgreen.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);
    Assert.assertFalse(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // no nulls, not repeating
    batch = makeStringBatch();
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);

    cmp2 = StringExpr.compare(redgreen, 0, redgreen.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);

    int cmp3 = StringExpr.compare(red, 0, red.length, outCol.vector[2],
        outCol.start[2], outCol.length[2]);
    Assert.assertEquals(0, cmp3);

    Assert.assertTrue(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // has nulls, is repeating
    batch = makeStringBatch();
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertFalse(outCol.noNulls);

    // no nulls, is repeating
    batch = makeStringBatch();
    batch.cols[0].isRepeating = true;
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[1];
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isRepeating);
    Assert.assertTrue(outCol.noNulls);
  }

  @Test
  public void testColConcatCol() {

    // has nulls, not repeating
    VectorizedRowBatch batch = makeStringBatch2In1Out();
    StringConcatColCol expr = new StringConcatColCol(0, 1, 2);
    expr.evaluate(batch);
    BytesColumnVector outCol = (BytesColumnVector) batch.cols[2];

    int cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertTrue(outCol.isNull[2]);
    int cmp2 = StringExpr.compare(greengreen, 0, greengreen.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);
    Assert.assertFalse(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // no nulls, not repeating
    batch = makeStringBatch2In1Out();
    batch.cols[0].noNulls = true;
    batch.cols[1].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);

    cmp2 = StringExpr.compare(greengreen, 0, greengreen.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp2);

    int cmp3 = StringExpr.compare(emptyString, 0, emptyString.length,
        outCol.vector[2], outCol.start[2], outCol.length[2]);
    Assert.assertEquals(0, cmp3);

    Assert.assertTrue(outCol.noNulls);
    Assert.assertFalse(outCol.isRepeating);

    // has nulls, is repeating

    batch = makeStringBatch2In1Out();
    batch.cols[0].isRepeating = true;                  // only left input repeating
    batch.cols[0].isNull[0] = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(true, outCol.isRepeating);
    Assert.assertEquals(true, outCol.isNull[0]);

       // same, but repeating input is not null

    batch = makeStringBatch2In1Out();
    batch.cols[0].isRepeating = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];
    Assert.assertEquals(false, outCol.isRepeating);  //TEST FAILED
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
    Assert.assertEquals(true, outCol.isNull[2]);

    batch = makeStringBatch2In1Out();
    batch.cols[1].isRepeating = true;                  // only right input repeating
    batch.cols[1].isNull[0] = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(true, outCol.isRepeating);
    Assert.assertEquals(true, outCol.isNull[0]);

    batch = makeStringBatch2In1Out();
    batch.cols[0].isRepeating = true;                  // both inputs repeat
    batch.cols[0].isNull[0] = true;
    batch.cols[1].isRepeating = true;
    batch.cols[1].isNull[0] = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(true, outCol.isRepeating);
    Assert.assertEquals(true, outCol.isNull[0]);

    // no nulls, is repeating
    batch = makeStringBatch2In1Out();
    batch.cols[1].isRepeating = true;             // only right input repeating and has no nulls
    batch.cols[1].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(false, outCol.isRepeating);
    Assert.assertEquals(false, outCol.isNull[0]);
    Assert.assertEquals(false, outCol.noNulls);
    Assert.assertEquals(true, outCol.isNull[2]);
    cmp = StringExpr.compare(greenred, 0, greenred.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp);

         // try again with left input also having no nulls
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    Assert.assertEquals(false, outCol.isRepeating);
    Assert.assertEquals(true,  outCol.noNulls);
    cmp = StringExpr.compare(red, 0, red.length, outCol.vector[2],
        outCol.start[2], outCol.length[2]);
    Assert.assertEquals(0, cmp);

    batch = makeStringBatch2In1Out();
    batch.cols[0].isRepeating = true;             // only left input repeating and has no nulls
    batch.cols[0].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(false, outCol.isRepeating);
    Assert.assertEquals(false, outCol.isNull[0]);
    Assert.assertEquals(false, outCol.noNulls);
    Assert.assertEquals(true, outCol.isNull[2]);
    cmp = StringExpr.compare(redgreen, 0, redgreen.length, outCol.vector[1],
        outCol.start[1], outCol.length[1]);
    Assert.assertEquals(0, cmp);

    batch = makeStringBatch2In1Out();
    batch.cols[0].isRepeating = true;                  // both inputs repeat
    batch.cols[0].noNulls = true;
    batch.cols[1].isRepeating = true;
    batch.cols[1].noNulls = true;
    expr.evaluate(batch);
    outCol = (BytesColumnVector) batch.cols[2];

    Assert.assertEquals(3, batch.size);
    Assert.assertEquals(true, outCol.isRepeating);
    Assert.assertEquals(false, outCol.isNull[0]);
    cmp = StringExpr.compare(redred, 0, redred.length, outCol.vector[0],
        outCol.start[0], outCol.length[0]);
    Assert.assertEquals(0, cmp);
  }
}