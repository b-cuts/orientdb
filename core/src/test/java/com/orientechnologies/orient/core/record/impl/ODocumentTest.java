package com.orientechnologies.orient.core.record.impl;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.id.OClusterPositionLong;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializer;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.ORecordSerializerBinary;
import com.orientechnologies.orient.core.serialization.serializer.record.string.ORecordSerializerSchemaAware2CSV;

import org.testng.Assert;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * @author <a href="mailto:enisher@gmail.com">Artem Orobets</a>
 */
public class ODocumentTest {
  @Test
  public void testCopyToCopiesEmptyFieldsTypesAndOwners() throws Exception {
    ODocument doc1 = new ODocument();

    ODocument doc2 = new ODocument().field("integer2", 123).field("string", "OrientDB").field("a", 123.3)

    .setFieldType("integer", OType.INTEGER).setFieldType("string", OType.STRING).setFieldType("binary", OType.BINARY);
    ODocumentInternal.addOwner(doc2, new ODocument());

    assertEquals(doc2.field("integer2"), 123);
    assertEquals(doc2.field("string"), "OrientDB");
    assertEquals(doc2.field("a"), 123.3);

    assertEquals(doc2.fieldType("integer"), OType.INTEGER);
    assertEquals(doc2.fieldType("string"), OType.STRING);
    assertEquals(doc2.fieldType("binary"), OType.BINARY);

    assertNotNull(doc2.getOwner());

    doc1.copyTo(doc2);

    assertEquals(doc2.field("integer2"), null);
    assertEquals(doc2.field("string"), null);
    assertEquals(doc2.field("a"), null);

    assertEquals(doc2.fieldType("integer"), null);
    assertEquals(doc2.fieldType("string"), null);
    assertEquals(doc2.fieldType("binary"), null);

    assertNull(doc2.getOwner());
  }

  @Test
  public void testClearResetsFieldTypes() throws Exception {
    ODocument doc = new ODocument();
    doc.setFieldType("integer", OType.INTEGER);
    doc.setFieldType("string", OType.STRING);
    doc.setFieldType("binary", OType.BINARY);

    assertEquals(doc.fieldType("integer"), OType.INTEGER);
    assertEquals(doc.fieldType("string"), OType.STRING);
    assertEquals(doc.fieldType("binary"), OType.BINARY);

    doc.clear();

    assertEquals(doc.fieldType("integer"), null);
    assertEquals(doc.fieldType("string"), null);
    assertEquals(doc.fieldType("binary"), null);
  }

  @Test
  public void testResetResetsFieldTypes() throws Exception {
    ODocument doc = new ODocument();
    doc.setFieldType("integer", OType.INTEGER);
    doc.setFieldType("string", OType.STRING);
    doc.setFieldType("binary", OType.BINARY);

    assertEquals(doc.fieldType("integer"), OType.INTEGER);
    assertEquals(doc.fieldType("string"), OType.STRING);
    assertEquals(doc.fieldType("binary"), OType.BINARY);

    doc.reset();

    assertEquals(doc.fieldType("integer"), null);
    assertEquals(doc.fieldType("string"), null);
    assertEquals(doc.fieldType("binary"), null);
  }

  @Test
  public void testKeepFieldType() throws Exception {
    ODocument doc = new ODocument();
    doc.field("integer", 10, OType.INTEGER);
    doc.field("string", 20, OType.STRING);
    doc.field("binary", new byte[] { 30 }, OType.BINARY);

    assertEquals(doc.fieldType("integer"), OType.INTEGER);
    assertEquals(doc.fieldType("string"), OType.STRING);
    assertEquals(doc.fieldType("binary"), OType.BINARY);
  }

  @Test
  public void testKeepFieldTypeSerialization() throws Exception {
    ODocument doc = new ODocument();
    doc.field("integer", 10, OType.INTEGER);
    doc.field("link", new ORecordId(1, new OClusterPositionLong(2)), OType.LINK);
    doc.field("string", 20, OType.STRING);
    doc.field("binary", new byte[] { 30 }, OType.BINARY);

    assertEquals(doc.fieldType("integer"), OType.INTEGER);
    assertEquals(doc.fieldType("link"), OType.LINK);
    assertEquals(doc.fieldType("string"), OType.STRING);
    assertEquals(doc.fieldType("binary"), OType.BINARY);
    ORecordSerializer ser = ODatabaseDocumentTx.getDefaultSerializer();
    byte[] bytes = ser.toStream(doc, false);
    doc = new ODocument();
    ser.fromStream(bytes, doc, null);
    assertEquals(doc.fieldType("integer"), OType.INTEGER);
    assertEquals(doc.fieldType("string"), OType.STRING);
    assertEquals(doc.fieldType("binary"), OType.BINARY);
    assertEquals(doc.fieldType("link"), OType.LINK);
  }
}
