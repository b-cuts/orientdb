/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.record.impl;

import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.ref.WeakReference;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.types.OModifiableInteger;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordInternal;
import com.orientechnologies.orient.core.db.record.ODetachable;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.OMultiValueChangeListener;
import com.orientechnologies.orient.core.db.record.OMultiValueChangeTimeLine;
import com.orientechnologies.orient.core.db.record.ORecordElement;
import com.orientechnologies.orient.core.db.record.ORecordLazyList;
import com.orientechnologies.orient.core.db.record.ORecordLazyMap;
import com.orientechnologies.orient.core.db.record.ORecordLazyMultiValue;
import com.orientechnologies.orient.core.db.record.ORecordLazySet;
import com.orientechnologies.orient.core.db.record.OTrackedList;
import com.orientechnologies.orient.core.db.record.OTrackedMap;
import com.orientechnologies.orient.core.db.record.OTrackedMultiValue;
import com.orientechnologies.orient.core.db.record.OTrackedSet;
import com.orientechnologies.orient.core.db.record.ridbag.ORidBag;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.exception.OSchemaException;
import com.orientechnologies.orient.core.exception.OValidationException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.iterator.OEmptyMapEntryIterator;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OSchemaShared;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordAbstract;
import com.orientechnologies.orient.core.record.ORecordListener;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.serialization.OBinaryProtocol;
import com.orientechnologies.orient.core.serialization.serializer.ONetworkThreadLocalSerializer;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.storage.OStorage;

/**
 * Document representation to handle values dynamically. Can be used in schema-less, schema-mixed and schema-full modes. Fields can
 * be added at run-time. Instances can be reused across calls by using the reset() before to re-use.
 */
@SuppressWarnings({ "unchecked" })
public class ODocument extends ORecordAbstract implements Iterable<Entry<String, Object>>, ORecordSchemaAware, ODetachable,
    Externalizable {

  private final ThreadLocal<OModifiableInteger>                          TO_STRING_DEPTH     = new ThreadLocal<OModifiableInteger>() {
                                                                                               @Override
                                                                                               protected OModifiableInteger initialValue() {
                                                                                                 return new OModifiableInteger();
                                                                                               }
                                                                                             };

  public static final byte                                               RECORD_TYPE         = 'd';
  protected static final String[]                                        EMPTY_STRINGS       = new String[] {};
  private static final long                                              serialVersionUID    = 1L;
  protected OClass                                                       _clazz;
  protected Map<String, Object>                                          _fieldValues;
  protected Map<String, Object>                                          _fieldOriginalValues;
  protected Map<String, OType>                                           _fieldTypes;
  protected Map<String, OSimpleMultiValueChangeListener<Object, Object>> _fieldChangeListeners;
  protected Map<String, OMultiValueChangeTimeLine<Object, Object>>       _fieldCollectionChangeTimeLines;
  protected boolean                                                      _trackingChanges    = true;
  protected boolean                                                      _ordered            = true;
  protected boolean                                                      _lazyLoad           = true;
  protected boolean                                                      _allowChainedAccess = true;
  protected transient List<WeakReference<ORecordElement>>                _owners             = null;

  /**
   * Internal constructor used on unmarshalling.
   */
  public ODocument() {
    setup();
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not persistent until {@link #save()}
   * is called.
   * 
   * @param iSource
   *          Raw stream
   */
  public ODocument(final byte[] iSource) {
    _source = iSource;
    setup();
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not persistent until {@link #save()}
   * is called.
   * 
   * @param iSource
   *          Raw stream as InputStream
   */
  public ODocument(final InputStream iSource) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    OIOUtils.copyStream(iSource, out, -1);
    _source = out.toByteArray();
    setup();
  }

  /**
   * Creates a new instance in memory linked by the Record Id to the persistent one. New instances are not persistent until
   * {@link #save()} is called.
   * 
   * @param iRID
   *          Record Id
   */
  public ODocument(final ORID iRID) {
    setup();
    _recordId = (ORecordId) iRID;
    _status = STATUS.NOT_LOADED;
    _dirty = false;
    _contentChanged = false;
  }

  /**
   * Creates a new instance in memory of the specified class, linked by the Record Id to the persistent one. New instances are not
   * persistent until {@link #save()} is called.
   * 
   * @param iClassName
   *          Class name
   * @param iRID
   *          Record Id
   */
  public ODocument(final String iClassName, final ORID iRID) {
    this(iClassName);
    _recordId = (ORecordId) iRID;

    final ODatabaseRecordInternal database = getDatabaseInternal();
    if (_recordId.clusterId > -1 && database.getStorageVersions().classesAreDetectedByClusterId()) {
      final OSchema schema = database.getMetadata().getSchema();
      final OClass cls = schema.getClassByClusterId(_recordId.clusterId);
      if (cls != null && !cls.getName().equals(iClassName))
        throw new IllegalArgumentException("Cluster id does not correspond class name should be " + iClassName + " but found "
            + cls.getName());
    }

    _dirty = false;
    _contentChanged = false;
    _status = STATUS.NOT_LOADED;
  }

  /**
   * Creates a new instance in memory of the specified class. New instances are not persistent until {@link #save()} is called.
   * 
   * @param iClassName
   *          Class name
   */
  public ODocument(final String iClassName) {
    setClassName(iClassName);
    setup();
  }

  /**
   * Creates a new instance in memory of the specified schema class. New instances are not persistent until {@link #save()} is
   * called. The database reference is taken from the thread local.
   * 
   * @param iClass
   *          OClass instance
   */
  public ODocument(final OClass iClass) {
    setup();
    _clazz = iClass;
  }

  /**
   * Fills a document passing the field array in form of pairs of field name and value.
   * 
   * @param iFields
   *          Array of field pairs
   */
  public ODocument(final Object[] iFields) {
    setup();
    if (iFields != null && iFields.length > 0)
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
  }

  /**
   * Fills a document passing a map of key/values where the key is the field name and the value the field's value.
   * 
   * @param iFieldMap
   *          Map of Object/Object
   */
  public ODocument(final Map<?, Object> iFieldMap) {
    setup();
    if (iFieldMap != null && !iFieldMap.isEmpty())
      for (Entry<?, Object> entry : iFieldMap.entrySet()) {
        field(entry.getKey().toString(), entry.getValue());
      }
  }

  /**
   * Fills a document passing the field names/values pair, where the first pair is mandatory.
   */
  public ODocument(final String iFieldName, final Object iFieldValue, final Object... iFields) {
    this(iFields);
    field(iFieldName, iFieldValue);
  }

  /**
   * Copies the current instance to a new one. Hasn't been choose the clone() to let ODocument return type. Once copied the new
   * instance has the same identity and values but all the internal structure are totally independent by the source.
   */
  public ODocument copy() {
    return (ODocument) copyTo(new ODocument());
  }

  /**
   * Copies all the fields into iDestination document.
   */
  @Override
  public ORecordAbstract copyTo(final ORecordAbstract iDestination) {
    // TODO: REMOVE THIS
    checkForFields();

    ODocument destination = (ODocument) iDestination;

    super.copyTo(iDestination);

    destination._ordered = _ordered;
    destination._clazz = _clazz;
    destination._trackingChanges = _trackingChanges;
    if (_owners != null)
      destination._owners = new ArrayList<WeakReference<ORecordElement>>(_owners);
    else
      destination._owners = null;

    if (_fieldValues != null) {
      destination._fieldValues = _fieldValues instanceof LinkedHashMap ? new LinkedHashMap<String, Object>()
          : new HashMap<String, Object>();
      for (Entry<String, Object> entry : _fieldValues.entrySet())
        ODocumentHelper.copyFieldValue(destination, entry);
    } else
      destination._fieldValues = null;

    if (_fieldTypes != null)
      destination._fieldTypes = new HashMap<String, OType>(_fieldTypes);
    else
      destination._fieldTypes = null;

    destination._fieldChangeListeners = null;
    destination._fieldCollectionChangeTimeLines = null;
    destination._fieldOriginalValues = null;
    destination.addAllMultiValueChangeListeners();

    destination._dirty = _dirty; // LEAVE IT AS LAST TO AVOID SOMETHING SET THE FLAG TO TRUE
    destination._contentChanged = _contentChanged;

    return destination;
  }

  @Override
  protected ODocument flatCopy() {
    if (isDirty())
      throw new IllegalStateException("Cannot execute a flat copy of a dirty record");

    final ODocument cloned = new ODocument();

    cloned.setOrdered(_ordered);
    cloned.fill(_recordId, _recordVersion, _source, false);
    return cloned;
  }

  /**
   * Returns an empty record as place-holder of the current. Used when a record is requested, but only the identity is needed.
   * 
   * @return placeholder of this document
   */
  public ORecord placeholder() {
    final ODocument cloned = new ODocument();
    cloned._source = null;
    cloned._recordId = _recordId.copy();
    cloned._status = STATUS.NOT_LOADED;
    cloned._dirty = false;
    cloned._contentChanged = false;
    return cloned;
  }

  /**
   * Detaches all the connected records. If new records are linked to the document the detaching cannot be completed and false will
   * be returned.
   * 
   * @return true if the record has been detached, otherwise false
   */
  public boolean detach() {
    boolean fullyDetached = true;

    if (_fieldValues != null) {
      Object fieldValue;
      for (Map.Entry<String, Object> entry : _fieldValues.entrySet()) {
        fieldValue = entry.getValue();

        if (fieldValue instanceof ORecord)
          if (((ORecord) fieldValue).getIdentity().isNew())
            fullyDetached = false;
          else
            _fieldValues.put(entry.getKey(), ((ORecord) fieldValue).getIdentity());

        if (fieldValue instanceof ODetachable) {
          if (!((ODetachable) fieldValue).detach())
            fullyDetached = false;
        }
      }
    }

    return fullyDetached;
  }

  /**
   * Loads the record using a fetch plan. Example:
   * <p>
   * <code>doc.load( "*:3" ); // LOAD THE DOCUMENT BY EARLY FETCHING UP TO 3rd LEVEL OF CONNECTIONS</code>
   * </p>
   * 
   * @param iFetchPlan
   *          Fetch plan to use
   */
  public ODocument load(final String iFetchPlan) {
    return load(iFetchPlan, false);
  }

  /**
   * Loads the record using a fetch plan. Example:
   * <p>
   * <code>doc.load( "*:3", true ); // LOAD THE DOCUMENT BY EARLY FETCHING UP TO 3rd LEVEL OF CONNECTIONS IGNORING THE CACHE</code>
   * </p>
   * 
   * @param iIgnoreCache
   *          Ignore the cache or use it
   */
  public ODocument load(final String iFetchPlan, boolean iIgnoreCache) {
    Object result;
    try {
      result = getDatabase().load(this, iFetchPlan, iIgnoreCache);
    } catch (Exception e) {
      throw new ORecordNotFoundException("The record with id '" + getIdentity() + "' was not found", e);
    }

    if (result == null)
      throw new ORecordNotFoundException("The record with id '" + getIdentity() + "' was not found");

    return (ODocument) result;
  }

  public ODocument load(final String iFetchPlan, boolean iIgnoreCache, boolean loadTombstone) {
    Object result;
    try {
      result = getDatabase().load(this, iFetchPlan, iIgnoreCache, loadTombstone, OStorage.LOCKING_STRATEGY.DEFAULT);
    } catch (Exception e) {
      throw new ORecordNotFoundException("The record with id '" + getIdentity() + "' was not found", e);
    }

    if (result == null)
      throw new ORecordNotFoundException("The record with id '" + getIdentity() + "' was not found");

    return (ODocument) result;
  }

  @Override
  public ODocument reload(final String iFetchPlan, final boolean iIgnoreCache) {
    super.reload(iFetchPlan, iIgnoreCache);
    if (!_lazyLoad) {
      checkForFields();
      checkForLoading();
    }
    return this;
  }

  public boolean hasSameContentOf(final ODocument iOther) {
    final ODatabaseRecordInternal currentDb = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    return ODocumentHelper.hasSameContentOf(this, currentDb, iOther, currentDb, null);
  }

  @Override
  public byte[] toStream() {
    if (_recordFormat == null)
      setup();
    return toStream(false);
  }

  protected byte[] toStream(final boolean iOnlyDelta) {
    STATUS prev = _status;
    _status = STATUS.MARSHALLING;
    try {
      if (ONetworkThreadLocalSerializer.getNetworkSerializer() != null)
        return ONetworkThreadLocalSerializer.getNetworkSerializer().toStream(this, iOnlyDelta);

      if (_source == null)
        _source = _recordFormat.toStream(this, iOnlyDelta);
    } finally {
      _status = prev;
    }
    invokeListenerEvent(ORecordListener.EVENT.MARSHALL);

    return _source;
  }

  /**
   * Dumps the instance as string.
   */
  @Override
  public String toString() {
    TO_STRING_DEPTH.get().increment();
    try {
      if (TO_STRING_DEPTH.get().intValue() > 1)
        return "<recursion:rid=" + (_recordId != null ? _recordId : "null") + ">";

      final boolean saveDirtyStatus = _dirty;
      final boolean oldUpdateContent = _contentChanged;

      try {
        final StringBuilder buffer = new StringBuilder(128);

        checkForFields();
        if (_clazz != null)
          buffer.append(_clazz.getStreamableName());

        if (_recordId != null) {
          if (_recordId.isValid())
            buffer.append(_recordId);
        }

        boolean first = true;
        for (Entry<String, Object> f : _fieldValues.entrySet()) {
          buffer.append(first ? '{' : ',');
          buffer.append(f.getKey());
          buffer.append(':');
          if (f.getValue() == null)
            buffer.append("null");
          else if (f.getValue() instanceof Collection<?> || f.getValue().getClass().isArray()) {
            buffer.append('[');
            buffer.append(OMultiValue.getSize(f.getValue()));
            buffer.append(']');
          } else if (f.getValue() instanceof ORecord) {
            final ORecord record = (ORecord) f.getValue();

            if (record.getIdentity().isValid())
              record.getIdentity().toString(buffer);
            else
              buffer.append(record.toString());
          } else
            buffer.append(f.getValue());

          if (first)
            first = false;
        }
        if (!first)
          buffer.append('}');

        if (_recordId != null && _recordId.isValid()) {
          buffer.append(" v");
          buffer.append(_recordVersion);
        }

        return buffer.toString();
      } finally {
        _dirty = saveDirtyStatus;
        _contentChanged = oldUpdateContent;
      }
    } finally {
      TO_STRING_DEPTH.get().decrement();
    }
  }

  /**
   * Fills the ODocument directly with the string representation of the document itself. Use it for faster insertion but pay
   * attention to respect the OrientDB record format.
   * <p>
   * <code>
   * record.reset();<br/>
   * record.setClassName("Account");<br/>
   * record.fromString(new String("Account@id:" + data.getCyclesDone() + ",name:'Luca',surname:'Garulli',birthDate:" + date.getTime()<br/>
   * + ",salary:" + 3000f + i));<br/>
   * record.save();<br/>
   * </code>
   * </p>
   * 
   * @param iValue
   */
  public void fromString(final String iValue) {
    _dirty = true;
    _contentChanged = true;
    _source = OBinaryProtocol.string2bytes(iValue);

    removeAllCollectionChangeListeners();

    _fieldCollectionChangeTimeLines = null;
    _fieldOriginalValues = null;
    _fieldTypes = null;
    _fieldValues = null;
  }

  /**
   * Returns the set of field names.
   */
  public String[] fieldNames() {
    checkForLoading();
    checkForFields();

    if (_fieldValues == null || _fieldValues.size() == 0)
      return EMPTY_STRINGS;

    return _fieldValues.keySet().toArray(new String[_fieldValues.size()]);
  }

  /**
   * Returns the array of field values.
   */
  public Object[] fieldValues() {
    checkForLoading();
    checkForFields();

    return _fieldValues.values().toArray(new Object[_fieldValues.size()]);
  }

  public <RET> RET rawField(final String iFieldName) {
    if (iFieldName == null || iFieldName.length() == 0)
      return null;

    checkForLoading();
    if (!checkForFields(iFieldName))
      // NO FIELDS
      return null;

    // OPTIMIZATION
    if (iFieldName.charAt(0) != '@' && OStringSerializerHelper.indexOf(iFieldName, 0, '.', '[') == -1)
      return (RET) _fieldValues.get(iFieldName);

    // NOT FOUND, PARSE THE FIELD NAME
    return (RET) ODocumentHelper.getFieldValue(this, iFieldName);
  }

  /**
   * Reads the field value.
   * 
   * @param iFieldName
   *          field name
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName) {
    RET value = this.rawField(iFieldName);

    final OType t = fieldType(iFieldName);

    if (!iFieldName.startsWith("@") && _lazyLoad && value instanceof ORID
        && (((ORID) value).isPersistent() || ((ORID) value).isNew()) && ODatabaseRecordThreadLocal.INSTANCE.isDefined()) {
      // CREATE THE DOCUMENT OBJECT IN LAZY WAY
      RET newValue = (RET) getDatabase().load((ORID) value);
      if (newValue != null) {
        value = newValue;
        if (!iFieldName.contains(".")) {
          removeCollectionChangeListener(iFieldName, _fieldValues.get(iFieldName));
          removeCollectionTimeLine(iFieldName);
          _fieldValues.put(iFieldName, value);
          addCollectionChangeListener(iFieldName, value);
        }
      }
    }

    // CHECK FOR CONVERSION
    if (t != null) {
      Object newValue = null;

      if (t == OType.BINARY && value instanceof String)
        newValue = OStringSerializerHelper.getBinaryContent(value);
      else if (t == OType.DATE && value instanceof Long)
        newValue = new Date((Long) value);
      else if ((t == OType.EMBEDDEDSET || t == OType.LINKSET) && value instanceof List)
        // CONVERT LIST TO SET
        newValue = ODocumentHelper.convertField(this, iFieldName, Set.class, value);
      else if ((t == OType.EMBEDDEDLIST || t == OType.LINKLIST) && value instanceof Set)
        // CONVERT SET TO LIST
        newValue = ODocumentHelper.convertField(this, iFieldName, List.class, value);

      if (newValue != null) {
        // VALUE CHANGED: SET THE NEW ONE
        removeCollectionChangeListener(iFieldName, null);
        removeCollectionTimeLine(iFieldName);
        _fieldValues.put(iFieldName, newValue);
        addCollectionChangeListener(iFieldName, newValue);

        value = (RET) newValue;
      }
    }

    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of ORID instead of the entire document by
   * passing ORID.class as iFieldType.
   * 
   * @param iFieldName
   *          field name
   * @param iFieldType
   *          Forced type.
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final Class<?> iFieldType) {
    RET value = this.rawField(iFieldName);

    if (value != null)
      value = ODocumentHelper.convertField(this, iFieldName, iFieldType, value);

    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of binary data.
   * 
   * @param iFieldName
   *          field name
   * @param iFieldType
   *          Forced type.
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final OType iFieldType) {
    setFieldType(iFieldName, iFieldType);
    return (RET) field(iFieldName);
  }

  /**
   * Writes the field value. This method sets the current document as dirty.
   * 
   * @param iFieldName
   *          field name. If contains dots (.) the change is applied to the nested documents in chain. To disable this feature call
   *          {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue
   *          field value
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods in chain.
   */
  public ODocument field(final String iFieldName, Object iPropertyValue) {
    return field(iFieldName, iPropertyValue, new OType[0]);
  }

  /**
   * Fills a document passing the field names/values.
   */
  public ODocument fields(final String iFieldName, final Object iFieldValue, final Object... iFields) {
    if (iFields != null && iFields.length % 2 != 0)
      throw new IllegalArgumentException("Fields must be passed in pairs as name and value");

    field(iFieldName, iFieldValue);
    if (iFields != null && iFields.length > 0)
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
    return this;
  }

  /**
   * Fills a document passing the field names/values as a Map<String,Object> where the keys are the field names and the values are
   * the field values.
   */
  public ODocument fields(final Map<String, Object> iMap) {
    if (iMap != null) {
      for (Entry<String, Object> entry : iMap.entrySet())
        field(entry.getKey(), entry.getValue());
    }
    return this;
  }

  /**
   * Writes the field value forcing the type. This method sets the current document as dirty.
   * 
   * 
   * 
   * @param iFieldName
   *          field name. If contains dots (.) the change is applied to the nested documents in chain. To disable this feature call
   *          {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue
   *          field value
   * @param iFieldType
   *          Forced type (not auto-determined)
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods in chain. If the updated
   *         document is another document (using the dot (.) notation) then the document returned is the changed one or NULL if no
   *         document has been found in chain
   */
  public ODocument field(String iFieldName, Object iPropertyValue, OType... iFieldType) {
    if ("@class".equals(iFieldName)) {
      setClassName(iPropertyValue.toString());
      return this;
    } else if ("@rid".equals(iFieldName)) {
      _recordId.fromString(iPropertyValue.toString());
      return this;
    }

    final int lastSep = _allowChainedAccess ? iFieldName.lastIndexOf('.') : -1;
    if (lastSep > -1) {
      // SUB PROPERTY GET 1 LEVEL BEFORE LAST
      final Object subObject = field(iFieldName.substring(0, lastSep));
      if (subObject != null) {
        final String subFieldName = iFieldName.substring(lastSep + 1);
        if (subObject instanceof ODocument) {
          // SUB-DOCUMENT
          ((ODocument) subObject).field(subFieldName, iPropertyValue);
          return (ODocument) (((ODocument) subObject).isEmbedded() ? this : subObject);
        } else if (subObject instanceof Map<?, ?>)
          // KEY/VALUE
          ((Map<String, Object>) subObject).put(subFieldName, iPropertyValue);
        else if (OMultiValue.isMultiValue(subObject)) {
          // APPLY CHANGE TO ALL THE ITEM IN SUB-COLLECTION
          for (Object subObjectItem : OMultiValue.getMultiValueIterable(subObject)) {
            if (subObjectItem instanceof ODocument) {
              // SUB-DOCUMENT, CHECK IF IT'S NOT LINKED
              if (!((ODocument) subObjectItem).isEmbedded())
                throw new IllegalArgumentException("Property '" + iFieldName
                    + "' points to linked collection of items. You can only change embedded documents in this way");
              ((ODocument) subObjectItem).field(subFieldName, iPropertyValue);
            } else if (subObjectItem instanceof Map<?, ?>) {
              // KEY/VALUE
              ((Map<String, Object>) subObjectItem).put(subFieldName, iPropertyValue);
            }
          }
          return this;
        }
      }
      return null;
    }

    iFieldName = checkFieldName(iFieldName);

    checkForLoading();
    checkForFields();

    final boolean knownProperty = _fieldValues.containsKey(iFieldName);
    final Object oldValue = _fieldValues.get(iFieldName);

    if (knownProperty)
      // CHECK IF IS REALLY CHANGED
      if (iPropertyValue == null) {
        if (oldValue == null)
          // BOTH NULL: UNCHANGED
          return this;
      } else {
        try {
          if (iPropertyValue.equals(oldValue)) {
            if (!(iPropertyValue instanceof ORecordElement))
              // SAME BUT NOT TRACKABLE: SET THE RECORD AS DIRTY TO BE SURE IT'S SAVED
              setDirty();

            // SAVE VALUE: UNCHANGED
            return this;
          }

        } catch (Exception e) {
          OLogManager.instance().warn(this, "Error on checking the value of property %s against the record %s", e, iFieldName,
              getIdentity());
        }
      }

    OType fieldType = deriveFieldType(iFieldName, iFieldType);

    if (oldValue instanceof ORidBag) {
      final ORidBag ridBag = (ORidBag) oldValue;
      ridBag.setOwner(null);
    } else if (oldValue instanceof ODocument) {
      ODocumentInternal.removeOwner((ODocument) oldValue, this);
    }

    if (iPropertyValue != null) {
      // CHECK FOR CONVERSION
      if (fieldType != null) {
        iPropertyValue = ODocumentHelper.convertField(this, iFieldName, fieldType.getDefaultJavaType(), iPropertyValue);
        if (fieldType.equals(OType.EMBEDDED) && iPropertyValue instanceof ODocument) {
          final ODocument embeddedDocument = (ODocument) iPropertyValue;
          ODocumentInternal.addOwner(embeddedDocument, this);
        }
      } else if (iPropertyValue instanceof Enum)
        iPropertyValue = iPropertyValue.toString();

      if (iPropertyValue instanceof ORidBag) {
        final ORidBag ridBag = (ORidBag) iPropertyValue;
        ridBag.setOwner(null); // in order to avoid IllegalStateException when ridBag changes the owner (ODocument.merge)
        ridBag.setOwner(this);
      }
    }

    removeCollectionChangeListener(iFieldName, _fieldValues.get(iFieldName));
    removeCollectionTimeLine(iFieldName);
    _fieldValues.put(iFieldName, iPropertyValue);
    addCollectionChangeListener(iFieldName, iPropertyValue);

    if (_status != STATUS.UNMARSHALLING) {
      setDirty();

      saveOldFieldValue(iFieldName, oldValue);
    }

    return this;
  }

  /**
   * Removes a field.
   */
  public Object removeField(final String iFieldName) {
    checkForLoading();
    checkForFields();

    final boolean knownProperty = _fieldValues.containsKey(iFieldName);
    final Object oldValue = _fieldValues.get(iFieldName);

    if (knownProperty && _trackingChanges) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (_fieldOriginalValues == null)
        _fieldOriginalValues = new HashMap<String, Object>();

      // INSERT IT ONLY IF NOT EXISTS TO AVOID LOOSE OF THE ORIGINAL VALUE (FUNDAMENTAL FOR INDEX HOOK)
      if (!_fieldOriginalValues.containsKey(iFieldName)) {
        _fieldOriginalValues.put(iFieldName, oldValue);
      }
    }

    removeCollectionTimeLine(iFieldName);
    removeCollectionChangeListener(iFieldName, oldValue);
    _fieldValues.remove(iFieldName);
    _source = null;

    setDirty();
    return oldValue;
  }

  /**
   * Merge current document with the document passed as parameter. If the field already exists then the conflicts are managed based
   * on the value of the parameter 'iConflictsOtherWins'.
   *
   * @param iOther
   *          Other ODocument instance to merge
   * @param iUpdateOnlyMode
   *          if true, the other document properties will always be added or overwritten. If false, the missed properties in the
   *          "other" document will be removed by original document
   * @param iMergeSingleItemsOfMultiValueFields
   *
   * @return
   */
  public ODocument merge(final ODocument iOther, boolean iUpdateOnlyMode, boolean iMergeSingleItemsOfMultiValueFields) {
    iOther.checkForLoading();
    iOther.checkForFields();

    if (_clazz == null && iOther.getSchemaClass() != null)
      _clazz = iOther.getSchemaClass();

    return merge(iOther._fieldValues, iUpdateOnlyMode, iMergeSingleItemsOfMultiValueFields);
  }

  /**
   * Merge current document with the document passed as parameter. If the field already exists then the conflicts are managed based
   * on the value of the parameter 'iConflictsOtherWins'.
   *
   * @param iOther
   *          Other ODocument instance to merge
   * @param iUpdateOnlyMode
   *          if true, the other document properties will always be added or overwritten. If false, the missed properties in the
   *          "other" document will be removed by original document
   * @param iMergeSingleItemsOfMultiValueFields
   *
   * @return
   */
  public ODocument merge(final Map<String, Object> iOther, final boolean iUpdateOnlyMode,
      boolean iMergeSingleItemsOfMultiValueFields) {
    checkForLoading();
    checkForFields();

    _source = null;

    for (String f : iOther.keySet()) {
      final Object value = field(f);
      final Object otherValue = iOther.get(f);

      if (containsField(f) && iMergeSingleItemsOfMultiValueFields) {
        if (value instanceof Map<?, ?>) {
          final Map<String, Object> map = (Map<String, Object>) value;
          final Map<String, Object> otherMap = (Map<String, Object>) otherValue;

          for (Entry<String, Object> entry : otherMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
          }
          continue;
        } else if (OMultiValue.isMultiValue(value)) {
          for (Object item : OMultiValue.getMultiValueIterable(otherValue)) {
            if (!OMultiValue.contains(value, item))
              OMultiValue.add(value, item);
          }

          // JUMP RAW REPLACE
          continue;
        }
      }

      // RESET THE FIELD TYPE
      setFieldType(f, null);

      boolean bagsMerged = false;
      if (value instanceof ORidBag && otherValue instanceof ORidBag)
        bagsMerged = ((ORidBag) value).tryMerge((ORidBag) otherValue, iMergeSingleItemsOfMultiValueFields);

      if (!bagsMerged && (value != null && !value.equals(otherValue)) || (value == null && otherValue != null))
        field(f, otherValue);
    }

    if (!iUpdateOnlyMode) {
      // REMOVE PROPERTIES NOT FOUND IN OTHER DOC
      for (String f : fieldNames())
        if (!iOther.containsKey(f))
          removeField(f);
    }

    return this;
  }

  /**
   * Returns list of changed fields. There are two types of changes:
   * <ol>
   * <li>Value of field itself was changed by calling of {@link #field(String, Object)} method for example.</li>
   * <li>Internal state of field was changed but was not saved. This case currently is applicable for for collections only.</li>
   * </ol>
   *
   * @return List of fields, values of which were changed.
   */
  public String[] getDirtyFields() {
    if ((_fieldOriginalValues == null || _fieldOriginalValues.isEmpty())
        && (_fieldCollectionChangeTimeLines == null || _fieldCollectionChangeTimeLines.isEmpty()))
      return EMPTY_STRINGS;

    final Set<String> dirtyFields = new HashSet<String>();
    if (_fieldOriginalValues != null)
      dirtyFields.addAll(_fieldOriginalValues.keySet());

    if (_fieldCollectionChangeTimeLines != null)
      dirtyFields.addAll(_fieldCollectionChangeTimeLines.keySet());

    return dirtyFields.toArray(new String[dirtyFields.size()]);
  }

  /**
   * Returns the original value of a field before it has been changed.
   *
   * @param iFieldName
   *          Property name to retrieve the original value
   */
  public Object getOriginalValue(final String iFieldName) {
    return _fieldOriginalValues != null ? _fieldOriginalValues.get(iFieldName) : null;
  }

  public OMultiValueChangeTimeLine<Object, Object> getCollectionTimeLine(final String iFieldName) {
    return _fieldCollectionChangeTimeLines != null ? _fieldCollectionChangeTimeLines.get(iFieldName) : null;
  }

  /**
   * Returns the iterator fields
   */
  public Iterator<Entry<String, Object>> iterator() {
    checkForLoading();
    checkForFields();

    if (_fieldValues == null)
      return OEmptyMapEntryIterator.INSTANCE;

    final Iterator<Entry<String, Object>> iterator = _fieldValues.entrySet().iterator();
    return new Iterator<Entry<String, Object>>() {
      private Entry<String, Object> current;

      public boolean hasNext() {
        return iterator.hasNext();
      }

      public Entry<String, Object> next() {
        current = iterator.next();
        return current;
      }

      public void remove() {
        iterator.remove();

        if (_trackingChanges) {
          // SAVE THE OLD VALUE IN A SEPARATE MAP
          if (_fieldOriginalValues == null)
            _fieldOriginalValues = new HashMap<String, Object>();

          // INSERT IT ONLY IF NOT EXISTS TO AVOID LOOSE OF THE ORIGINAL VALUE (FUNDAMENTAL FOR INDEX HOOK)
          if (!_fieldOriginalValues.containsKey(current.getKey())) {
            _fieldOriginalValues.put(current.getKey(), current.getValue());
          }
        }

        removeCollectionChangeListener(current.getKey(), current.getValue());
        removeCollectionTimeLine(current.getKey());
      }
    };
  }

  /**
   * Checks if a field exists.
   *
   * @return True if exists, otherwise false.
   */
  public boolean containsField(final String iFieldName) {
    if (iFieldName == null)
      return false;

    checkForLoading();
    checkForFields(iFieldName);
    return _fieldValues.containsKey(iFieldName);
  }

  /**
   * Internal.
   */
  protected byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Returns true if the record has some owner.
   */
  public boolean hasOwners() {
    return _owners != null && !_owners.isEmpty();
  }

  /**
   * Internal.
   *
   * @return this
   */
  protected void addOwner(final ORecordElement iOwner) {
    if (_owners == null)
      _owners = new ArrayList<WeakReference<ORecordElement>>();

    boolean found = false;
    for (WeakReference<ORecordElement> _owner : _owners) {
      final ORecordElement e = _owner.get();
      if (e == iOwner) {
        found = true;
        break;
      }
    }

    if (!found)
      this._owners.add(new WeakReference<ORecordElement>(iOwner));

  }

  @Override
  public ORecordElement getOwner() {
    if (_owners == null)
      return null;

    for (WeakReference<ORecordElement> _owner : _owners) {
      final ORecordElement e = _owner.get();
      if (e != null)
        return e;
    }

    return null;
  }

  public Iterable<ORecordElement> getOwners() {
    if (_owners == null)
      return Collections.emptyList();

    final List<ORecordElement> result = new ArrayList<ORecordElement>();
    for (WeakReference<ORecordElement> o : _owners)
      result.add(o.get());

    return result;
  }

  protected void removeOwner(final ORecordElement iRecordElement) {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (int i = 0; i < _owners.size(); ++i) {
        e = _owners.get(i).get();
        if (e == iRecordElement) {
          _owners.remove(i);
          break;
        }
      }
    }
  }

  /**
   * Propagates the dirty status to the owner, if any. This happens when the object is embedded in another one.
   */
  @Override
  public ORecordAbstract setDirty() {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (WeakReference<ORecordElement> o : _owners) {
        e = o.get();
        if (e != null)
          e.setDirty();
      }
    }
    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD _SOURCE IS NULL
    checkForFields();

    return super.setDirty();
  }

  @Override
  public void setDirtyNoChanged() {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (WeakReference<ORecordElement> o : _owners) {
        e = o.get();
        if (e != null)
          e.setDirtyNoChanged();
      }
    }

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD _SOURCE IS NULL
    checkForFields();

    super.setDirtyNoChanged();
  }

  @Override
  public void onBeforeIdentityChanged(final ORecord iRecord) {
    super.onBeforeIdentityChanged(iRecord);
    if (_owners != null) {
      final List<WeakReference<ORecordElement>> temp = new ArrayList<WeakReference<ORecordElement>>(_owners);

      ORecordElement e;
      for (WeakReference<ORecordElement> o : temp) {
        e = o.get();
        if (e != null)
          e.onBeforeIdentityChanged(iRecord);
      }
    }
  }

  @Override
  public void onAfterIdentityChanged(final ORecord iRecord) {
    super.onAfterIdentityChanged(iRecord);
    if (_owners != null) {
      final List<WeakReference<ORecordElement>> temp = new ArrayList<WeakReference<ORecordElement>>(_owners);

      ORecordElement e;
      for (WeakReference<ORecordElement> o : temp) {
        e = o.get();
        if (e != null)
          e.onAfterIdentityChanged(iRecord);
      }
    }
  }

  @Override
  public ODocument fromStream(final byte[] iRecordBuffer) {
    removeAllCollectionChangeListeners();

    _fieldValues = null;
    _fieldTypes = null;
    _fieldOriginalValues = null;
    _fieldChangeListeners = null;
    _fieldCollectionChangeTimeLines = null;
    _contentChanged = false;

    super.fromStream(iRecordBuffer);

    if (!_lazyLoad) {
      checkForFields();
      checkForLoading();
    }

    return this;
  }

  /**
   * Returns the forced field type if any.
   *
   * @param iFieldName
   *          name of field to check
   */
  public OType fieldType(final String iFieldName) {
    return _fieldTypes != null ? _fieldTypes.get(iFieldName) : null;
  }

  @Override
  public ODocument unload() {
    super.unload();
    internalReset();
    return this;
  }

  /**
   * <p>
   * Clears all the field values and types. Clears only record content, but saves its identity.
   * </p>
   *
   * <p>
   * The following code will clear all data from specified document.
   * </p>
   * <code>
   *   doc.clear();
   *   doc.save();
   * </code>
   *
   * @return this
   * @see #reset()
   */
  @Override
  public ODocument clear() {
    super.clear();
    internalReset();
    _owners = null;
    return this;
  }

  /**
   * <p>
   * Resets the record values and class type to being reused. It's like you create a ODocument from scratch. This method is handy
   * when you want to insert a bunch of documents and don't want to strain GC.
   * </p>
   *
   * <p>
   * The following code will create a new document in database.
   * </p>
   * <code>
   *   doc.clear();
   *   doc.save();
   * </code>
   *
   * <p>
   * IMPORTANT! This can be used only if no transactions are begun.
   * </p>
   *
   * @return this
   * @throws IllegalStateException
   *           if transaction is begun.
   *
   * @see #clear()
   */
  @Override
  public ODocument reset() {
    ODatabaseRecord db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    if (db != null && db.getTransaction().isActive())
      throw new IllegalStateException("Cannot reset documents during a transaction. Create a new one each time");

    super.reset();
    _clazz = null;
    internalReset();

    if (_fieldOriginalValues != null)
      _fieldOriginalValues.clear();
    _owners = null;
    return this;
  }

  /**
   * Rollbacks changes to the loaded version without reloading the document. Works only if tracking changes is enabled @see
   * {@link #isTrackingChanges()} and {@link #setTrackingChanges(boolean)} methods.
   */
  public ODocument undo() {
    if (!_trackingChanges)
      throw new OConfigurationException("Cannot undo the document because tracking of changes is disabled");

    for (Entry<String, Object> entry : _fieldOriginalValues.entrySet()) {
      final Object value = entry.getValue();
      if (value == null)
        _fieldValues.remove(entry.getKey());
      else
        _fieldValues.put(entry.getKey(), entry.getValue());
    }

    return this;
  }

  public boolean isLazyLoad() {
    return _lazyLoad;
  }

  public void setLazyLoad(final boolean iLazyLoad) {
    this._lazyLoad = iLazyLoad;
    checkForFields();

    if (_fieldValues != null) {
      // PROPAGATE LAZINESS TO THE FIELDS
      for (Entry<String, Object> field : _fieldValues.entrySet()) {
        if (field.getValue() instanceof ORecordLazyMultiValue)
          ((ORecordLazyMultiValue) field.getValue()).setAutoConvertToRecord(false);
      }
    }
  }

  public boolean isTrackingChanges() {
    return _trackingChanges;
  }

  /**
   * Enabled or disabled the tracking of changes in the document. This is needed by some triggers like
   * {@link com.orientechnologies.orient.core.index.OClassIndexManager} to determine what fields are changed to update indexes.
   *
   * @param iTrackingChanges
   *          True to enable it, otherwise false
   * @return this
   */
  public ODocument setTrackingChanges(final boolean iTrackingChanges) {
    this._trackingChanges = iTrackingChanges;
    if (!iTrackingChanges) {
      // FREE RESOURCES
      this._fieldOriginalValues = null;
      removeAllCollectionChangeListeners();
      _fieldChangeListeners = null;
      _fieldCollectionChangeTimeLines = null;
    } else {
      addAllMultiValueChangeListeners();
    }
    return this;
  }

  public boolean isOrdered() {
    return _ordered;
  }

  public ODocument setOrdered(final boolean iOrdered) {
    this._ordered = iOrdered;
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj))
      return false;

    return this == obj || _recordId.isValid();
  }

  @Override
  public int hashCode() {
    if (_recordId.isValid())
      return super.hashCode();

    return System.identityHashCode(this);
  }

  /**
   * Returns the number of fields in memory.
   */
  public int fields() {
    checkForLoading();
    checkForFields();
    return _fieldValues == null ? 0 : _fieldValues.size();
  }

  public boolean isEmpty() {
    checkForLoading();
    checkForFields();
    return _fieldValues == null || _fieldValues.isEmpty();
  }

  public boolean isEmbedded() {
    return _owners != null && !_owners.isEmpty();
  }

  /**
   * Sets the field type. This overrides the schema property settings if any.
   *
   * @param iFieldName
   *          Field name
   * @param iFieldType
   *          Type to set between OType enumaration values
   */
  public ODocument setFieldType(final String iFieldName, final OType iFieldType) {
    if (iFieldType != null) {
      // SET THE FORCED TYPE
      if (_fieldTypes == null)
        _fieldTypes = new HashMap<String, OType>();
      _fieldTypes.put(iFieldName, iFieldType);
    } else if (_fieldTypes != null) {
      // REMOVE THE FIELD TYPE
      _fieldTypes.remove(iFieldName);
      if (_fieldTypes.size() == 0)
        // EMPTY: OPTIMIZE IT BY REMOVING THE ENTIRE MAP
        _fieldTypes = null;
    }
    return this;
  }

  @Override
  public ODocument save() {
    return save(false);
  }

  @Override
  public ODocument save(final String iClusterName) {
    return save(iClusterName, false);
  }

  @Override
  public ODocument save(boolean forceCreate) {
    if (_clazz != null)
      return save(getDatabase().getClusterNameById(_clazz.getClusterForNewInstance()), forceCreate);

    convertAllMultiValuesToTrackedVersions();
    validate();
    return (ODocument) super.save(forceCreate);
  }

  @Override
  public ODocument save(final String iClusterName, boolean forceCreate) {
    convertAllMultiValuesToTrackedVersions();
    validate();
    return (ODocument) super.save(iClusterName, forceCreate);
  }

  /*
   * Initializes the object if has been unserialized
   */
  public boolean deserializeFields(final String... iFields) {
    if (_source == null)
      // ALREADY UNMARSHALLED OR JUST EMPTY
      return true;

    if (iFields != null && iFields.length > 0) {
      // EXTRACT REAL FIELD NAMES
      for (int i = 0; i < iFields.length; ++i) {
        final String f = iFields[i];
        if (!f.startsWith("@")) {
          int pos1 = f.indexOf('[');
          int pos2 = f.indexOf('.');
          if (pos1 > -1 || pos2 > -1) {
            int pos = pos1 > -1 ? pos1 : pos2;
            if (pos2 > -1 && pos2 < pos)
              pos = pos2;

            // REPLACE THE FIELD NAME
            iFields[i] = f.substring(0, pos);
          }
        }
      }

      // CHECK IF HAS BEEN ALREADY UNMARSHALLED
      if (_fieldValues != null && !_fieldValues.isEmpty()) {
        boolean allFound = true;
        for (String f : iFields)
          if (!f.startsWith("@") && !_fieldValues.containsKey(f)) {
            allFound = false;
            break;
          }

        if (allFound)
          // ALL THE REQUESTED FIELDS HAVE BEEN LOADED BEFORE AND AVAILABLES, AVOID UNMARSHALLIGN
          return true;
      }
    }

    if (_recordFormat == null)
      setup();

    _status = ORecordElement.STATUS.UNMARSHALLING;
    try {
      _recordFormat.fromStream(_source, this, iFields);
    } finally {
      _status = ORecordElement.STATUS.LOADED;
    }

    if (iFields != null && iFields.length > 0) {
      if (iFields[0].startsWith("@"))
        // ATTRIBUTE
        return true;

      // PARTIAL UNMARSHALLING
      if (_fieldValues != null && !_fieldValues.isEmpty())
        for (String f : iFields)
          if (_fieldValues.containsKey(f))
            return true;

      // NO FIELDS FOUND
      return false;
    } else if (_source != null)
      // FULL UNMARSHALLING
      _source = null;

    return true;
  }

  /**
   * Converts all non-tracked collections implementations contained in document fields to tracked ones.
   *
   * @see OTrackedMultiValue
   */
  protected void convertAllMultiValuesToTrackedVersions() {
    if (_fieldValues == null)
      return;

    final Map<String, Object> fieldsToUpdate = new HashMap<String, Object>();

    for (Map.Entry<String, Object> fieldEntry : _fieldValues.entrySet()) {
      final Object fieldValue = fieldEntry.getValue();
      if (!(fieldValue instanceof Collection<?>) && !(fieldValue instanceof Map<?, ?>))
        continue;
      if (fieldValue instanceof OTrackedMultiValue) {
        addCollectionChangeListener(fieldEntry.getKey(), (OTrackedMultiValue<Object, Object>) fieldValue);
        continue;
      }

      OType fieldType = fieldType(fieldEntry.getKey());
      if (fieldType == null && _clazz != null) {
        final OProperty prop = _clazz.getProperty(fieldEntry.getKey());
        fieldType = prop != null ? prop.getType() : null;
      }

      if (fieldType == null
          || !(OType.EMBEDDEDLIST.equals(fieldType) || OType.EMBEDDEDMAP.equals(fieldType) || OType.EMBEDDEDSET.equals(fieldType)
              || OType.LINKSET.equals(fieldType) || OType.LINKLIST.equals(fieldType) || OType.LINKMAP.equals(fieldType)))
        continue;
      Object newValue = null;
      if (fieldValue instanceof List && fieldType.equals(OType.EMBEDDEDLIST))
        newValue = new OTrackedList<Object>(this, (List<?>) fieldValue, null);
      else if (fieldValue instanceof Set && fieldType.equals(OType.EMBEDDEDSET))
        newValue = new OTrackedSet<Object>(this, (Set<OIdentifiable>) fieldValue, null);
      else if (fieldValue instanceof Map && fieldType.equals(OType.EMBEDDEDMAP))
        newValue = new OTrackedMap<OIdentifiable>(this, (Map<Object, OIdentifiable>) fieldValue, null);
      else if (fieldValue instanceof Set && fieldType.equals(OType.LINKSET))
        newValue = new ORecordLazySet(this, (Collection<OIdentifiable>) fieldValue);
      else if (fieldValue instanceof List && fieldType.equals(OType.LINKLIST))
        newValue = new ORecordLazyList(this, (List<OIdentifiable>) fieldValue);
      else if (fieldValue instanceof Map && fieldType.equals(OType.LINKMAP))
        newValue = new ORecordLazyMap(this, (Map<Object, OIdentifiable>) fieldValue);
      if (newValue != null) {
        addCollectionChangeListener(fieldEntry.getKey(), (OTrackedMultiValue<Object, Object>) newValue);
        fieldsToUpdate.put(fieldEntry.getKey(), newValue);
      }
    }

    _fieldValues.putAll(fieldsToUpdate);
  }

  @Override
  public void writeExternal(ObjectOutput stream) throws IOException {
    final byte[] idBuffer = _recordId.toStream();
    stream.writeInt(idBuffer.length);
    stream.write(idBuffer);

    _recordVersion.getSerializer().writeTo(stream, _recordVersion);

    final byte[] content = toStream();
    stream.writeInt(content.length);
    stream.write(content);

    stream.writeBoolean(_dirty);
  }

  @Override
  public void readExternal(ObjectInput stream) throws IOException, ClassNotFoundException {
    final byte[] idBuffer = new byte[stream.readInt()];
    stream.readFully(idBuffer);
    _recordId.fromStream(idBuffer);

    _recordVersion.getSerializer().readFrom(stream, _recordVersion);

    final int len = stream.readInt();
    final byte[] content = new byte[len];
    stream.readFully(content);

    fromStream(content);

    _dirty = stream.readBoolean();
  }

  /**
   * Returns the behavior of field() methods allowing access to the sub documents with dot notation ('.'). Default is true. Set it
   * to false if you allow to store properties with the dot.
   */
  public boolean isAllowChainedAccess() {
    return _allowChainedAccess;
  }

  /**
   * Change the behavior of field() methods allowing access to the sub documents with dot notation ('.'). Default is true. Set it to
   * false if you allow to store properties with the dot.
   */
  public ODocument setAllowChainedAccess(final boolean _allowChainedAccess) {
    this._allowChainedAccess = _allowChainedAccess;
    return this;
  }

  protected void internalReset() {
    removeAllCollectionChangeListeners();

    if (_fieldCollectionChangeTimeLines != null)
      _fieldCollectionChangeTimeLines.clear();

    if (_fieldValues != null)
      _fieldValues.clear();

    if (_fieldTypes != null)
      _fieldTypes.clear();
  }

  protected boolean checkForFields(final String... iFields) {
    if (_fieldValues == null)
      _fieldValues = _ordered ? new LinkedHashMap<String, Object>() : new HashMap<String, Object>();

    if (_status == ORecordElement.STATUS.LOADED && _source != null)
      // POPULATE FIELDS LAZY
      return deserializeFields(iFields);

    return true;
  }

  /**
   * Internal.
   */
  @Override
  protected void setup() {
    super.setup();

    final ODatabaseRecordInternal db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    if (db != null)
      _recordFormat = db.getSerializer();

    if (_recordFormat == null)
      // GET THE DEFAULT ONE
      _recordFormat = ODatabaseDocumentTx.getDefaultSerializer();
  }

  protected String checkFieldName(final String iFieldName) {
    final Character c = OSchemaShared.checkNameIfValid(iFieldName);
    if (c != null)
      throw new IllegalArgumentException("Invalid field name '" + iFieldName + "'. Character '" + c + "' is invalid");

    return iFieldName;
  }

  protected void clearSource() {
    this._source = null;
  }

  private void saveOldFieldValue(String iFieldName, Object oldValue) {
    if (_trackingChanges && _recordId.isValid()) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP ONLY IF TRACKING IS ACTIVE AND THE RECORD IS NOT NEW
      if (_fieldOriginalValues == null)
        _fieldOriginalValues = new HashMap<String, Object>();

      // INSERT IT ONLY IF NOT EXISTS TO AVOID LOOSE OF THE ORIGINAL VALUE (FUNDAMENTAL FOR INDEX HOOK)
      if (!_fieldOriginalValues.containsKey(iFieldName))
        _fieldOriginalValues.put(iFieldName, oldValue);
    }
  }

  private OType deriveFieldType(String iFieldName, OType[] iFieldType) {
    OType fieldType;

    if (iFieldType != null && iFieldType.length == 1) {
      setFieldType(iFieldName, iFieldType[0]);
      fieldType = iFieldType[0];
    } else
      fieldType = null;

    if (fieldType == null && _clazz != null) {
      // SCHEMAFULL?
      final OProperty prop = _clazz.getProperty(iFieldName);
      if (prop != null)
        fieldType = prop.getType();
    }
    return fieldType;
  }

  private void addCollectionChangeListener(final String fieldName, final Object fieldValue) {
    if (!(fieldValue instanceof OTrackedMultiValue))
      return;
    addCollectionChangeListener(fieldName, (OTrackedMultiValue<Object, Object>) fieldValue);
  }

  private void addCollectionChangeListener(final String fieldName, final OTrackedMultiValue<Object, Object> multiValue) {
    if (_fieldChangeListeners == null)
      _fieldChangeListeners = new HashMap<String, OSimpleMultiValueChangeListener<Object, Object>>();

    if (!_fieldChangeListeners.containsKey(fieldName)) {
      final OSimpleMultiValueChangeListener<Object, Object> listener = new OSimpleMultiValueChangeListener<Object, Object>(this,
          fieldName);
      multiValue.addChangeListener(listener);
      _fieldChangeListeners.put(fieldName, listener);
    }
  }

  private void removeAllCollectionChangeListeners() {
    if (_fieldValues == null)
      return;

    for (final Map.Entry<String, Object> field : _fieldValues.entrySet()) {
      removeCollectionChangeListener(field.getKey(), field.getValue());
    }
    _fieldChangeListeners = null;
  }

  private void addAllMultiValueChangeListeners() {
    if (_fieldValues == null)
      return;

    for (final Map.Entry<String, Object> field : _fieldValues.entrySet()) {
      addCollectionChangeListener(field.getKey(), field.getValue());
    }
  }

  private void removeCollectionChangeListener(final String fieldName, Object fieldValue) {
    if (_fieldChangeListeners == null)
      return;

    final OMultiValueChangeListener<Object, Object> changeListener = _fieldChangeListeners.remove(fieldName);

    if (!(fieldValue instanceof OTrackedMultiValue))
      return;

    if (changeListener != null) {
      final OTrackedMultiValue<Object, Object> multiValue = (OTrackedMultiValue<Object, Object>) fieldValue;
      multiValue.removeRecordChangeListener(changeListener);
    }
  }

  private void removeCollectionTimeLine(final String fieldName) {
    if (_fieldCollectionChangeTimeLines == null)
      return;

    _fieldCollectionChangeTimeLines.remove(fieldName);
  }

  public void setClassName(final String iClassName) {
    if (iClassName == null) {
      _clazz = null;
      return;
    }

    setClass(getDatabase().getMetadata().getSchema().getOrCreateClass(iClassName));
  }

  public void setClassNameIfExists(final String iClassName) {
    if (iClassName == null) {
      _clazz = null;
      return;
    }

    setClass(getDatabase().getMetadata().getSchema().getClass(iClassName));
  }

  protected void setClass(final OClass iClass) {
    if (iClass != null && iClass.isAbstract())
      throw new OSchemaException("Cannot create a document of the abstract class '" + iClass + "'");

    _clazz = iClass;
  }

  public OClass getSchemaClass() {
    if (_clazz == null) {
      final ODatabaseRecordInternal database = getDatabaseIfDefinedInternal();
      if (database != null && database.getStorageVersions() != null
          && database.getStorageVersions().classesAreDetectedByClusterId()) {
        if (_recordId.clusterId < 0) {
          checkForLoading();
          checkForFields("@class");
        } else {
          final OSchema schema = database.getMetadata().getSchema();
          if (schema != null)
            _clazz = schema.getClassByClusterId(_recordId.clusterId);
        }
      } else {
        // CLASS NOT FOUND: CHECK IF NEED LOADING AND UNMARSHALLING
        checkForLoading();
        checkForFields("@class");
      }
    }

    return _clazz;
  }

  public String getClassName() {
    if (_clazz == null)
      getSchemaClass();
    return _clazz != null ? _clazz.getName() : null;
  }

  protected static void validateField(ODocument iRecord, OProperty p) throws OValidationException {
    final Object fieldValue;

    if (iRecord.containsField(p.getName())) {
      // AVOID CONVERSIONS: FASTER!
      fieldValue = iRecord.rawField(p.getName());

      if (p.isNotNull() && fieldValue == null)
        // NULLITY
        throw new OValidationException("The field '" + p.getFullName() + "' cannot be null, record: " + iRecord);

      if (fieldValue != null && p.getRegexp() != null) {
        // REGEXP
        if (!fieldValue.toString().matches(p.getRegexp()))
          throw new OValidationException("The field '" + p.getFullName() + "' does not match the regular expression '"
              + p.getRegexp() + "'. Field value is: " + fieldValue + ", record: " + iRecord);
      }

    } else {
      if (p.isMandatory())
        throw new OValidationException("The field '" + p.getFullName() + "' is mandatory, but not found on record: " + iRecord);
      fieldValue = null;
    }

    final OType type = p.getType();

    if (fieldValue != null && type != null) {
      // CHECK TYPE
      switch (type) {
      case LINK:
        validateLink(p, fieldValue);
        break;
      case LINKLIST:
        if (!(fieldValue instanceof List))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as LINKLIST but an incompatible type is used. Value: " + fieldValue);
        validateLinkCollection(p, (Collection<Object>) fieldValue);
        break;
      case LINKSET:
        if (!(fieldValue instanceof Set))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as LINKSET but an incompatible type is used. Value: " + fieldValue);
        validateLinkCollection(p, (Collection<Object>) fieldValue);
        break;
      case LINKMAP:
        if (!(fieldValue instanceof Map))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as LINKMAP but an incompatible type is used. Value: " + fieldValue);
        validateLinkCollection(p, ((Map<?, Object>) fieldValue).values());
        break;

      case EMBEDDED:
        validateEmbedded(p, fieldValue);
        break;
      case EMBEDDEDLIST:
        if (!(fieldValue instanceof List))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as EMBEDDEDLIST but an incompatible type is used. Value: " + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Object item : ((List<?>) fieldValue))
            validateEmbedded(p, item);
        } else if (p.getLinkedType() != null) {
          for (Object item : ((List<?>) fieldValue))
            validateType(p, item);
        }
        break;
      case EMBEDDEDSET:
        if (!(fieldValue instanceof Set))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as EMBEDDEDSET but an incompatible type is used. Value: " + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Object item : ((Set<?>) fieldValue))
            validateEmbedded(p, item);
        } else if (p.getLinkedType() != null) {
          for (Object item : ((Set<?>) fieldValue))
            validateType(p, item);
        }
        break;
      case EMBEDDEDMAP:
        if (!(fieldValue instanceof Map))
          throw new OValidationException("The field '" + p.getFullName()
              + "' has been declared as EMBEDDEDMAP but an incompatible type is used. Value: " + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Entry<?, ?> entry : ((Map<?, ?>) fieldValue).entrySet())
            validateEmbedded(p, entry.getValue());
        } else if (p.getLinkedType() != null) {
          for (Entry<?, ?> entry : ((Map<?, ?>) fieldValue).entrySet())
            validateType(p, entry.getValue());
        }
        break;
      }
    }

    if (p.getMin() != null) {
      // MIN
      final String min = p.getMin();

      if (p.getType().equals(OType.STRING) && (fieldValue != null && ((String) fieldValue).length() < Integer.parseInt(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains fewer characters than " + min + " requested");
      else if (p.getType().equals(OType.BINARY) && (fieldValue != null && ((byte[]) fieldValue).length < Integer.parseInt(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains fewer bytes than " + min + " requested");
      else if (p.getType().equals(OType.INTEGER) && (fieldValue != null && type.asInt(fieldValue) < Integer.parseInt(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' is less than " + min);
      else if (p.getType().equals(OType.LONG) && (fieldValue != null && type.asLong(fieldValue) < Long.parseLong(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' is less than " + min);
      else if (p.getType().equals(OType.FLOAT) && (fieldValue != null && type.asFloat(fieldValue) < Float.parseFloat(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' is less than " + min);
      else if (p.getType().equals(OType.DOUBLE) && (fieldValue != null && type.asDouble(fieldValue) < Double.parseDouble(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' is less than " + min);
      else if (p.getType().equals(OType.DATE)) {
        try {
          if (fieldValue != null
              && ((Date) fieldValue).before(iRecord.getDatabaseInternal().getStorage().getConfiguration().getDateFormatInstance()
                  .parse(min)))
            throw new OValidationException("The field '" + p.getFullName() + "' contains the date " + fieldValue
                + " which precedes the first acceptable date (" + min + ")");
        } catch (ParseException e) {
        }
      } else if (p.getType().equals(OType.DATETIME)) {
        try {
          if (fieldValue != null
              && ((Date) fieldValue).before(iRecord.getDatabaseInternal().getStorage().getConfiguration()
                  .getDateTimeFormatInstance().parse(min)))
            throw new OValidationException("The field '" + p.getFullName() + "' contains the datetime " + fieldValue
                + " which precedes the first acceptable datetime (" + min + ")");
        } catch (ParseException e) {
        }
      } else if ((p.getType().equals(OType.EMBEDDEDLIST) || p.getType().equals(OType.EMBEDDEDSET)
          || p.getType().equals(OType.LINKLIST) || p.getType().equals(OType.LINKSET))
          && (fieldValue != null && ((Collection<?>) fieldValue).size() < Integer.parseInt(min)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains fewer items than " + min + " requested");
    }

    if (p.getMax() != null) {
      // MAX
      final String max = p.getMax();

      if (p.getType().equals(OType.STRING) && (fieldValue != null && ((String) fieldValue).length() > Integer.parseInt(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains more characters than " + max + " requested");
      else if (p.getType().equals(OType.BINARY) && (fieldValue != null && ((byte[]) fieldValue).length > Integer.parseInt(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains more bytes than " + max + " requested");
      else if (p.getType().equals(OType.INTEGER) && (fieldValue != null && type.asInt(fieldValue) > Integer.parseInt(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' is greater than " + max);
      else if (p.getType().equals(OType.LONG) && (fieldValue != null && type.asLong(fieldValue) > Long.parseLong(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' is greater than " + max);
      else if (p.getType().equals(OType.FLOAT) && (fieldValue != null && type.asFloat(fieldValue) > Float.parseFloat(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' is greater than " + max);
      else if (p.getType().equals(OType.DOUBLE) && (fieldValue != null && type.asDouble(fieldValue) > Double.parseDouble(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' is greater than " + max);
      else if (p.getType().equals(OType.DATE)) {
        try {
          if (fieldValue != null
              && ((Date) fieldValue).before(iRecord.getDatabaseInternal().getStorage().getConfiguration().getDateFormatInstance()
                  .parse(max)))
            throw new OValidationException("The field '" + p.getFullName() + "' contains the date " + fieldValue
                + " which is after the last acceptable date (" + max + ")");
        } catch (ParseException e) {
        }
      } else if (p.getType().equals(OType.DATETIME)) {
        try {
          if (fieldValue != null
              && ((Date) fieldValue).before(iRecord.getDatabaseInternal().getStorage().getConfiguration()
                  .getDateTimeFormatInstance().parse(max)))
            throw new OValidationException("The field '" + p.getFullName() + "' contains the datetime " + fieldValue
                + " which is after the last acceptable datetime (" + max + ")");
        } catch (ParseException e) {
        }
      } else if ((p.getType().equals(OType.EMBEDDEDLIST) || p.getType().equals(OType.EMBEDDEDSET)
          || p.getType().equals(OType.LINKLIST) || p.getType().equals(OType.LINKSET))
          && (fieldValue != null && ((Collection<?>) fieldValue).size() > Integer.parseInt(max)))
        throw new OValidationException("The field '" + p.getFullName() + "' contains more items than " + max + " requested");
    }

    if (p.isReadonly() && iRecord instanceof ODocument && !iRecord.getRecordVersion().isTombstone()) {
      for (String f : ((ODocument) iRecord).getDirtyFields())
        if (f.equals(p.getName())) {
          // check if the field is actually changed by equal.
          // this is due to a limitation in the merge algorithm used server side marking all non simple fields as dirty
          Object orgVal = ((ODocument) iRecord).getOriginalValue(f);
          boolean simple = fieldValue != null ? OType.isSimpleType(fieldValue) : OType.isSimpleType(orgVal);
          if ((simple) || (fieldValue != null && orgVal == null) || (fieldValue == null && orgVal != null)
              || (fieldValue != null && !fieldValue.equals(orgVal)))
            throw new OValidationException("The field '" + p.getFullName()
                + "' is immutable and cannot be altered. Field value is: " + ((ODocument) iRecord).field(f));
        }
    }
  }

  protected static void validateLinkCollection(final OProperty property, Collection<Object> values) {
    if (property.getLinkedClass() != null)
      for (Object object : values) {
        validateLink(property, object);
      }
  }

  protected static void validateType(final OProperty p, final Object value) {
    if (value != null)
      if (OType.convert(value, p.getLinkedType().getDefaultJavaType()) == null)
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '"
            + p.getLinkedType() + "' but the value is " + value);
  }

  protected static void validateLink(final OProperty p, final Object fieldValue) {
    if (fieldValue == null)
      throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
          + " but contains a null record (probably a deleted record?)");

    final ORecord linkedRecord;
    if (fieldValue instanceof OIdentifiable)
      linkedRecord = ((OIdentifiable) fieldValue).getRecord();
    else if (fieldValue instanceof String)
      linkedRecord = new ORecordId((String) fieldValue).getRecord();
    else
      throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
          + " but the value is not a record or a record-id");

    if (linkedRecord != null && p.getLinkedClass() != null) {
      if (!(linkedRecord instanceof ODocument))
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '"
            + p.getLinkedClass() + "' but the value is the record " + linkedRecord.getIdentity() + " that is not a document");

      final ODocument doc = (ODocument) linkedRecord;

      // AT THIS POINT CHECK THE CLASS ONLY IF != NULL BECAUSE IN CASE OF GRAPHS THE RECORD COULD BE PARTIAL
      if (doc.getSchemaClass() != null && !p.getLinkedClass().isSuperClassOf(doc.getSchemaClass()))
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '"
            + p.getLinkedClass().getName() + "' but the value is the document " + linkedRecord.getIdentity() + " of class '"
            + doc.getSchemaClass() + "'");
    }
  }

  protected static void validateEmbedded(final OProperty p, final Object fieldValue) {
    if (fieldValue instanceof ORecordId)
      throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
          + " but the value is the RecordID " + fieldValue);
    else if (fieldValue instanceof OIdentifiable) {
      if (((OIdentifiable) fieldValue).getIdentity().isValid())
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
            + " but the value is a document with the valid RecordID " + fieldValue);

      final OClass embeddedClass = p.getLinkedClass();
      if (embeddedClass != null) {
        final ORecord rec = ((OIdentifiable) fieldValue).getRecord();
        if (!(rec instanceof ODocument))
          throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
              + " with linked class '" + embeddedClass + "' but the record was not a document");

        final ODocument doc = (ODocument) rec;
        if (doc.getSchemaClass() == null)
          throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
              + " with linked class '" + embeddedClass + "' but the record has no class");

        if (!(doc.getSchemaClass().isSubClassOf(embeddedClass)))
          throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
              + " with linked class '" + embeddedClass + "' but the record is of class '" + doc.getSchemaClass().getName()
              + "' that is not a subclass of that");
      }

    } else
      throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
          + " but an incompatible type is used. Value: " + fieldValue);
  }

  /**
   * Validates the record following the declared constraints defined in schema such as mandatory, notNull, min, max, regexp, etc. If
   * the schema is not defined for the current class or there are not constraints then the validation is ignored.
   * 
   * @see OProperty
   * @throws OValidationException
   *           if the document breaks some validation constraints defined in the schema
   */
  public void validate() throws OValidationException {
    if (ODatabaseRecordThreadLocal.INSTANCE.isDefined() && !getDatabase().isValidationEnabled())
      return;

    checkForLoading();
    checkForFields();

    if (_clazz != null) {
      if (_clazz.isStrictMode()) {
        // CHECK IF ALL FIELDS ARE DEFINED
        for (String f : fieldNames()) {
          if (_clazz.getProperty(f) == null)
            throw new OValidationException("Found additional field '" + f + "'. It cannot be added because the schema class '"
                + _clazz.getName() + "' is defined as STRICT");
        }
      }

      for (OProperty p : _clazz.properties()) {
        validateField(this, p);
      }
    }
  }

}
