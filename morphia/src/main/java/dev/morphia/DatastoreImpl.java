package dev.morphia;

import com.mongodb.BasicDBObject;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.DBRef;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.DBCollectionUpdateOptions;
import com.mongodb.client.model.ValidationOptions;
import dev.morphia.aggregation.AggregationPipeline;
import dev.morphia.aggregation.AggregationPipelineImpl;
import dev.morphia.annotations.CappedAt;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.NotSaved;
import dev.morphia.annotations.PostPersist;
import dev.morphia.annotations.Validation;
import dev.morphia.annotations.Version;
import dev.morphia.mapping.MappedClass;
import dev.morphia.mapping.MappedField;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.MappingException;
import dev.morphia.mapping.cache.EntityCache;
import dev.morphia.mapping.lazy.proxy.ProxyHelper;
import dev.morphia.query.DefaultQueryFactory;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.QueryException;
import dev.morphia.query.QueryFactory;
import dev.morphia.query.QueryImpl;
import dev.morphia.query.UpdateException;
import dev.morphia.query.UpdateOperations;
import dev.morphia.query.UpdateOpsImpl;
import dev.morphia.query.UpdateResults;
import org.bson.codecs.configuration.CodecRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.mongodb.BasicDBObject.parse;
import static com.mongodb.BasicDBObjectBuilder.start;
import static com.mongodb.DBCollection.ID_FIELD_NAME;
import static com.mongodb.MongoClientSettings.getDefaultCodecRegistry;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/**
 * A generic (type-safe) wrapper around mongodb collections
 *
 * @morphia.internal
 */
class DatastoreImpl implements AdvancedDatastore {
    private static final Logger LOG = LoggerFactory.getLogger(DatastoreImpl.class);

    private final Morphia morphia;
    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final IndexHelper indexHelper;
    private DB db;
    private Mapper mapper;
    private WriteConcern defConcern;

    private volatile QueryFactory queryFactory = new DefaultQueryFactory();

    /**
     * Create a new DatastoreImpl
     *
     * @param morphia     the Morphia instance
     * @param mongoClient the connection to the MongoDB instance
     * @param dbName      the name of the database for this data store.
     * @deprecated This is not meant to be directly instantiated by end user code.  Use
     * {@link Morphia#createDatastore(MongoClient, Mapper, String)}
     */
    DatastoreImpl(final Morphia morphia, final MongoClient mongoClient, final String dbName) {
        this.morphia = morphia;
        this.mapper = morphia.getMapper();
        this.mongoClient = mongoClient;
        this.database = mongoClient.getDatabase(dbName)
                                   .withCodecRegistry(fromRegistries(mongoClient.getMongoClientOptions().getCodecRegistry(),
                                       getDefaultCodecRegistry()));
        this.db = mongoClient.getDB(dbName);
        this.defConcern = mongoClient.getWriteConcern();
        this.indexHelper = new IndexHelper(mapper, database);
    }

    /**
     * Creates a copy of this Datastore and all its configuration but with a new database
     *
     * @param database the new database to use for operations
     * @return the new Datastore instance
     * @deprecated use {@link Morphia#createDatastore(MongoClient, Mapper, String)}
     */
    @Deprecated
    public DatastoreImpl copy(final String database) {
        return new DatastoreImpl(morphia, mongoClient, database);
    }

    /**
     * @param source the initial type/collection to aggregate against
     * @return a new query bound to the kind (a specific {@link DBCollection})
     */
    @Override
    public AggregationPipeline createAggregation(final Class source) {
        return new AggregationPipelineImpl(this, getCollection(source), source);
    }

    @Override
    public AggregationPipeline createAggregation(final String collection, final Class<?> clazz) {
        return new AggregationPipelineImpl(this, getDB().getCollection(collection), clazz);
    }

    @Override
    public <T> Query<T> createQuery(final Class<T> collection) {
        return newQuery(collection, getCollection(collection));
    }

    @Override
    public <T> UpdateOperations<T> createUpdateOperations(final Class<T> clazz) {
        return new UpdateOpsImpl<>(clazz, getMapper());
    }

    @Override
    public <T> WriteResult delete(final Query<T> query, final DeleteOptions options) {
        final QueryImpl queryImpl = (QueryImpl) query;

        DBCollection dbColl = queryImpl.getCollection();
        // TODO remove this after testing.
        if (dbColl == null) {
            dbColl = getCollection(queryImpl.getEntityClass());
        }

        return dbColl.remove(queryImpl.getQueryObject(), enforceWriteConcern(options, queryImpl.getEntityClass()).getOptions());
    }

    @Override
    public <T> WriteResult delete(final Query<T> query) {
        return delete(query, new DeleteOptions().writeConcern(getWriteConcern(((QueryImpl) query).getEntityClass())));
    }

    @Override
    public <T> WriteResult delete(final T entity) {
        return delete(entity, new DeleteOptions().writeConcern(getWriteConcern(entity)));
    }

    /**
     * Deletes the given entity (by @Id), with the WriteConcern
     *
     * @param entity  the entity to delete
     * @param options the options to use when deleting
     * @return results of the delete
     */
    @Override
    public <T> WriteResult delete(final T entity, final DeleteOptions options) {
        final T wrapped = ProxyHelper.unwrap(entity);
        if (wrapped instanceof Class<?>) {
            throw new MappingException("Did you mean to delete all documents? -- delete(ds.createQuery(???.class))");
        }
        try {
            return delete(createQuery(wrapped.getClass()).filter("_id", mapper.getId(wrapped)), options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void ensureCaps() {
        for (final MappedClass mc : mapper.getMappedClasses()) {
            if (mc.getEntityAnnotation() != null && mc.getEntityAnnotation().cap().value() > 0) {
                final CappedAt cap = mc.getEntityAnnotation().cap();
                final String collName = mapper.getCollectionName(mc.getClazz());
                final BasicDBObject dbCapOpts = new BasicDBObject("capped", true);
                if (cap.value() > 0) {
                    dbCapOpts.put("size", cap.value());
                }
                if (cap.count() > 0) {
                    dbCapOpts.put("max", cap.count());
                }
                final DB database = getDB();
                if (database.getCollectionNames().contains(collName)) {
                    final CommandResult dbResult = database.command(start("collstats", collName).get());
                    if (dbResult.getBoolean("capped")) {
                        LOG.debug("DBCollection already exists and is capped already; doing nothing. " + dbResult);
                    } else {
                        LOG.warn("DBCollection already exists with same name(" + collName
                                 + ") and is not capped; not creating capped version!");
                    }
                } else {
                    getDB().createCollection(collName, dbCapOpts);
                    LOG.debug("Created capped DBCollection (" + collName + ") with opts " + dbCapOpts);
                }
            }
        }
    }

    @Override
    public void enableDocumentValidation() {
        for (final MappedClass mc : mapper.getMappedClasses()) {
            process(mc, (Validation) mc.getAnnotation(Validation.class));
        }
    }

    void process(final MappedClass mc, final Validation validation) {
        if (validation != null) {
            String collectionName = mc.getCollectionName();
            CommandResult result = getDB()
                                       .command(new BasicDBObject("collMod", collectionName)
                                                    .append("validator", parse(validation.value()))
                                                    .append("validationLevel", validation.level().getValue())
                                                    .append("validationAction", validation.action().getValue())
                                               );

            if (!result.ok()) {
                if (result.getInt("code") == 26) {
                    ValidationOptions options = new ValidationOptions()
                                                    .validator(parse(validation.value()))
                                                    .validationLevel(validation.level())
                                                    .validationAction(validation.action());
                    getDatabase().createCollection(collectionName, new CreateCollectionOptions().validationOptions(options));
                } else {
                    result.throwOnError();
                }
            }
        }
    }

    @Override
    public <T> Query<T> find(final Class<T> clazz) {
        return createQuery(clazz);
    }

    @Override
    public <T> T findAndDelete(final Query<T> query) {
        return findAndDelete(query, new FindAndModifyOptions());
    }

    @Override
    public <T> T findAndDelete(final Query<T> query, final FindAndModifyOptions options) {
        final QueryImpl<T> queryImpl = (QueryImpl<T>) query;
        DBCollection dbColl = queryImpl.getCollection();
        if (dbColl == null) {
            dbColl = getCollection(queryImpl.getEntityClass());
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing findAndModify(" + dbColl.getName() + ") with delete ...");
        }

        FindAndModifyOptions copy = enforceWriteConcern(options, queryImpl.getEntityClass())
                                        .copy()
                                        .projection(queryImpl.getFieldsObject())
                                        .sort(queryImpl.getSortObject())
                                        .returnNew(false)
                                        .upsert(false)
                                        .remove(true);

        final DBObject result = dbColl.findAndModify(queryImpl.getQueryObject(), copy.getOptions());

        return mapper.fromDBObject(this, queryImpl.getEntityClass(), result, createCache());
    }

    @Override
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations, final FindAndModifyOptions options) {
        final QueryImpl<T> queryImpl = (QueryImpl<T>) query;

        DBCollection dbColl = queryImpl.getCollection();
        // TODO remove this after testing.
        if (dbColl == null) {
            dbColl = getCollection(queryImpl.getEntityClass());
        }

        if (LOG.isTraceEnabled()) {
            LOG.info("Executing findAndModify(" + dbColl.getName() + ") with update ");
        }

        updateForVersioning(queryImpl, operations);
        DBObject res = dbColl.findAndModify(queryImpl.getQueryObject(), options.copy()
                                                                           .sort(queryImpl.getSortObject())
                                                                           .projection(queryImpl.getFieldsObject())
                                                                           .update(((UpdateOpsImpl<T>) operations).getOps())
                                                                           .getOptions());

        return mapper.fromDBObject(this, queryImpl.getEntityClass(), res, createCache());

    }

    @Override
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations) {
        return findAndModify(query, operations, new FindAndModifyOptions()
                                                    .returnNew(true));
    }

    @Override
    @Deprecated
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations, final boolean oldVersion) {
        return findAndModify(query, operations, new FindAndModifyOptions()
                                                    .returnNew(!oldVersion)
                                                    .upsert(false));
    }

    @Override
    @Deprecated
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations, final boolean oldVersion,
                               final boolean createIfMissing) {
        return findAndModify(query, operations, new FindAndModifyOptions()
                                                    .returnNew(!oldVersion)
                                                    .upsert(createIfMissing));

    }

    private <T> void updateForVersioning(final QueryImpl<T> query, final UpdateOperations<T> operations) {
        final MappedClass mc = mapper.getMappedClass(query.getEntityClass());

        if (!mc.getFieldsAnnotatedWith(Version.class).isEmpty()) {
            operations.inc(mc.getMappedVersionField().getNameToStore());
        }

    }

    @Override
    public <T, V> Query<T> get(final Class<T> clazz, final Iterable<V> ids) {
        return find(clazz).disableValidation().filter("_id" + " in", ids).enableValidation();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(final T entity) {
        return (T) find(entity.getClass()).filter("_id", getMapper().getId(entity)).first();
    }

    @Override
    public <T> T getByKey(final Class<T> clazz, final Key<T> key) {
        final String collectionName = mapper.getCollectionName(clazz);
        final String keyCollection = mapper.updateCollection(key);
        if (!collectionName.equals(keyCollection)) {
            throw new RuntimeException("collection names don't match for key and class: " + collectionName + " != " + keyCollection);
        }

        Object id = key.getId();
        if (id instanceof DBObject) {
            ((DBObject) id).removeField(mapper.getOptions().getDiscriminatorField());
        }
        return find(clazz).filter("_id", id)
                   .first(new FindOptions().limit(1));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T> List<T> getByKeys(final Class<T> clazz, final Iterable<Key<T>> keys) {

        final Map<String, List<Key>> kindMap = new HashMap<>();
        final List<T> entities = new ArrayList<>();
        for (final Key<?> key : keys) {
            mapper.updateCollection(key);

            if (kindMap.containsKey(key.getCollection())) {
                kindMap.get(key.getCollection()).add(key);
            } else {
                kindMap.put(key.getCollection(), new ArrayList<>(singletonList((Key) key)));
            }
        }
        for (final Map.Entry<String, List<Key>> entry : kindMap.entrySet()) {
            final List<Key> kindKeys = entry.getValue();

            final List<Object> objIds = new ArrayList<>();
            for (final Key key : kindKeys) {
                objIds.add(key.getId());
            }
            final List kindResults = find(entry.getKey()).disableValidation().filter("_id in", objIds)
                                                         .find()
                                                         .toList();
            entities.addAll(kindResults);
        }

        // TODO: order them based on the incoming Keys.
        return entities;
    }

    @Override
    public <T> List<T> getByKeys(final Iterable<Key<T>> keys) {
        return getByKeys(null, keys);
    }

    /**
     * @param obj the value to search with
     * @return the DBCollection
     * @deprecated this is an internal method.  no replacement is planned.
     */
    @Deprecated
    public DBCollection getCollection(final Object obj) {
        if (obj == null) {
            return null;
        }
        return getCollection(obj instanceof Class ? (Class) obj : obj.getClass());
    }

    @Override
    public DBCollection getCollection(final Class clazz) {
        final String collName = mapper.getCollectionName(clazz);
        return getDB().getCollection(collName);
    }

    private <T> MongoCollection<T> getMongoCollection(final Class<T> clazz) {
        return getMongoCollection(mapper.getCollectionName(clazz), clazz);
    }

    @SuppressWarnings("unchecked")
    private <T> MongoCollection<T> getMongoCollection(final String name, final Class<T> clazz) {
        final MongoCollection<T> collection = database.getCollection(name, clazz);
        return enforceWriteConcern(collection, clazz);
    }

    @Override
    public DB getDB() {
        return db;
    }

    @Override
    public MongoDatabase getDatabase() {
        return database;
    }

    @Override
    public WriteConcern getDefaultWriteConcern() {
        return defConcern;
    }

    @Override
    public void setDefaultWriteConcern(final WriteConcern wc) {
        defConcern = wc;
    }

    @Override
    @Deprecated
    // use mapper instead.
    public <T> Key<T> getKey(final T entity) {
        return mapper.getKey(entity);
    }

    @Override
    public MongoClient getMongo() {
        return mongoClient;
    }

    @Override
    public QueryFactory getQueryFactory() {
        return queryFactory;
    }

    @Override
    public void setQueryFactory(final QueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public <T> Key<T> merge(final T entity) {
        return merge(entity, getWriteConcern(entity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Key<T> merge(final T entity, final WriteConcern wc) {
        T unwrapped = entity;
        final LinkedHashMap<Object, DBObject> involvedObjects = new LinkedHashMap<>();
        final DBObject dbObj = mapper.toDBObject(unwrapped, involvedObjects);
        final Key<T> key = getKey(unwrapped);
        unwrapped = ProxyHelper.unwrap(unwrapped);
        final Object id = mapper.getId(unwrapped);
        if (id == null) {
            throw new MappingException("Could not get id for " + unwrapped.getClass().getName());
        }

        // remove (immutable) _id field for update.
        final Object idValue = dbObj.get("_id");
        dbObj.removeField("_id");

        WriteResult wr;

        final MappedClass mc = mapper.getMappedClass(unwrapped);
        final DBCollection dbColl = getCollection(unwrapped);

        // try to do an update if there is a @Version field
        final DBObject set = new BasicDBObject("$set", dbObj);
        wr = tryVersionedUpdate(dbColl, unwrapped, set, idValue, new InsertOptions().writeConcern(wc), mc);

        if (wr == null) {
            final QueryImpl<T> query = (QueryImpl<T>) createQuery(unwrapped.getClass()).filter("_id", id);
            wr = update(query, set, new UpdateOptions().writeConcern(wc)).getWriteResult();
        }

        final UpdateResults res = new UpdateResults(wr);

        if (res.getUpdatedCount() == 0) {
            throw new UpdateException("Nothing updated");
        }

        dbObj.put("_id", idValue);
        postSaveOperations(Collections.<Object>singletonList(entity), involvedObjects, false, dbColl.getName());
        return key;
    }

    @Override
    public <T> Query<T> queryByExample(final T ex) {
        return queryByExample(getCollection(ex), ex);
    }

    @Override
    public <T> Iterable<Key<T>> save(final Iterable<T> entities) {
        Iterator<T> iterator = entities.iterator();
        return !iterator.hasNext()
               ? Collections.emptyList()
               : save(entities, getWriteConcern(iterator.next()));
    }

    private <T> Iterable<Key<T>> save(final Iterable<T> entities, final WriteConcern wc) {
        return save(entities, new InsertOptions().writeConcern(wc));
    }

    @Override
    public <T> Iterable<Key<T>> save(final Iterable<T> entities, final InsertOptions options) {
        final List<Key<T>> savedKeys = new ArrayList<>();
        for (final T ent : entities) {
            savedKeys.add(save(ent, options));
        }
        return savedKeys;

    }

    @Override
    public <T> Key<T> save(final T entity) {
        return save(entity, new InsertOptions());
    }

    @Override
    public <T> Key<T> save(final T entity, final InsertOptions options) {
        if (entity == null) {
            throw new UpdateException("Can not persist a null entity");
        }

        final T unwrapped = ProxyHelper.unwrap(entity);
        return save(getCollection(unwrapped), unwrapped, enforceWriteConcern(options, entity.getClass()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> UpdateResults update(final T entity, final UpdateOperations<T> operations) {
        if (entity instanceof Query) {
            return update((Query<T>) entity, operations);
        }

        final MappedClass mc = mapper.getMappedClass(entity);
        Query<?> query = createQuery(mapper.getMappedClass(entity).getClazz())
                             .disableValidation()
                             .filter("_id", mapper.getId(entity));
        if (!mc.getFieldsAnnotatedWith(Version.class).isEmpty()) {
            final MappedField field = mc.getFieldsAnnotatedWith(Version.class).get(0);
            query.field(field.getNameToStore()).equal(field.getFieldValue(entity));
        }

        return update((Query<T>) query, operations);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> UpdateResults update(final Key<T> key, final UpdateOperations<T> operations) {
        Class<T> clazz = (Class<T>) key.getType();
        if (clazz == null) {
            clazz = mapper.getClassFromCollection(key.getCollection());
        }
        return update(createQuery(clazz).disableValidation().filter("_id", key.getId()), operations, new UpdateOptions());
    }

    @Override
    public <T> UpdateResults update(final Query<T> query, final UpdateOperations<T> operations) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(false)
                                             .multi(true)
                                             .writeConcern(getWriteConcern(((QueryImpl) query).getEntityClass())));
    }

    @Override
    @Deprecated
    public <T> UpdateResults update(final Query<T> query, final UpdateOperations<T> operations, final boolean createIfMissing) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(createIfMissing)
                                             .multi(true)
                                             .writeConcern(getWriteConcern(((QueryImpl) query).getEntityClass())));
    }

    @Override
    @Deprecated
    public <T> UpdateResults update(final Query<T> query, final UpdateOperations<T> operations, final boolean createIfMissing,
                                    final WriteConcern wc) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(createIfMissing)
                                             .multi(true)
                                             .writeConcern(wc));
    }

    @Override
    @Deprecated
    public <T> UpdateResults updateFirst(final Query<T> query, final UpdateOperations<T> operations) {
        return update(query, operations, new UpdateOptions());
    }

    @Override
    @Deprecated
    public <T> UpdateResults updateFirst(final Query<T> query, final UpdateOperations<T> operations, final boolean createIfMissing) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(createIfMissing));

    }

    @Override
    @Deprecated
    public <T> UpdateResults updateFirst(final Query<T> query, final UpdateOperations<T> operations, final boolean createIfMissing,
                                         final WriteConcern wc) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(createIfMissing)
                                             .writeConcern(wc));
    }

    @Override
    @Deprecated
    public <T> UpdateResults updateFirst(final Query<T> query, final T entity, final boolean createIfMissing) {
        final QueryImpl<T> queryImpl = (QueryImpl<T>) query;

        if (getMapper().getMappedClass(entity).getMappedVersionField() != null) {
            throw new UnsupportedOperationException("updateFirst() is not supported with versioned entities");
        }

        final LinkedHashMap<Object, DBObject> involvedObjects = new LinkedHashMap<>();
        final DBObject dbObj = mapper.toDBObject(entity, involvedObjects);

        final UpdateResults res = update(queryImpl, dbObj, new UpdateOptions()
                                                           .upsert(createIfMissing)
                                                           .writeConcern(getWriteConcern(entity)));

        // update _id field
        if (res.getInsertedCount() > 0) {
            dbObj.put("_id", res.getNewId());
        }

        postSaveOperations(singletonList(entity), involvedObjects, false, getCollection(entity).getName());
        return res;
    }

    @Override
    public <T> Query<T> createQuery(final String collection, final Class<T> type) {
        return newQuery(type, getDB().getCollection(collection));
    }

    @Override
    public <T> Query<T> createQuery(final Class<T> clazz, final DBObject q) {
        return newQuery(clazz, getCollection(clazz), q);
    }

    @Override
    public <T, V> DBRef createRef(final Class<T> clazz, final V id) {
        if (id == null) {
            throw new MappingException("Could not get id for " + clazz.getName());
        }
        return new DBRef(getCollection(clazz).getName(), id);
    }

    @Override
    public <T> DBRef createRef(final T entity) {
        final T wrapped = ProxyHelper.unwrap(entity);
        final Object id = mapper.getId(wrapped);
        if (id == null) {
            throw new MappingException("Could not get id for " + wrapped.getClass().getName());
        }
        return createRef(wrapped.getClass(), id);
    }

    @Override
    public <T> UpdateOperations<T> createUpdateOperations(final Class<T> type, final DBObject ops) {
        final UpdateOpsImpl<T> upOps = (UpdateOpsImpl<T>) createUpdateOperations(type);
        upOps.setOps(ops);
        return upOps;
    }

    @Override
    public void ensureIndexes() {
        for (final MappedClass mc : mapper.getMappedClasses()) {
            indexHelper.createIndex(getMongoCollection(mc.getClazz()), mc);
        }
    }

    @Override
    public <T> void ensureIndexes(final Class<T> clazz) {
        indexHelper.createIndex(getMongoCollection(clazz), mapper.getMappedClass(clazz));
    }

    @Override
    public <T> Query<T> find(final String collection) {
        return newQuery(mapper.getClassFromCollection(collection), getDB().getCollection(collection));
    }

    @Override
    public <T> Key<T> insert(final T entity) {
        return insert(entity, getWriteConcern(entity));
    }

    private <T> Key<T> insert(final T entity, final WriteConcern wc) {
        return insert(entity, new InsertOptions().writeConcern(wc));
    }

    @Override
    public <T> Key<T> insert(final T entity, final InsertOptions options) {
        final T unwrapped = ProxyHelper.unwrap(entity);
        return insert(getCollection(unwrapped), unwrapped, options);
    }

    @Override
    public <T> Iterable<Key<T>> insert(final Iterable<T> entities, final InsertOptions options) {
        Iterator<T> iterator = entities.iterator();
        return !iterator.hasNext()
               ? Collections.emptyList()
               : insert(getCollection(iterator.next()), entities, options);
    }

    @Override
    public <T> Query<T> queryByExample(final String collection, final T ex) {
        return queryByExample(getDB().getCollection(collection), ex);
    }

    /**
     * @return the Mapper used by this Datastore
     */
    public Mapper getMapper() {
        return mapper;
    }

    /**
     * Sets the Mapper this Datastore uses
     *
     * @param mapper the new Mapper
     */
    public void setMapper(final Mapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Inserts entities in to the database
     *
     * @param entities the entities to insert
     * @param <T>      the type of the entities
     * @return the keys of entities
     */
    @Override
    public <T> Iterable<Key<T>> insert(final Iterable<T> entities) {
        return insert(entities, new InsertOptions()
                                    .writeConcern(defConcern));
    }

    /**
     * Inserts an entity in to the database
     *
     * @param collection the collection to query against
     * @param entity     the entity to insert
     * @param wc         the WriteConcern to use when deleting
     * @param <T>        the type of the entities
     * @return the key of entity
     */
    public <T> Key<T> insert(final String collection, final T entity, final WriteConcern wc) {
        return insert(getCollection(collection), ProxyHelper.unwrap(entity), new InsertOptions().writeConcern(wc));
    }

    private DBCollection getCollection(final String kind) {
        if (kind == null) {
            return null;
        }
        return getDB().getCollection(kind);
    }

    @Deprecated
    protected Object getId(final Object entity) {
        return mapper.getId(entity);
    }

    protected <T> Key<T> insert(final DBCollection dbColl, final T entity, final InsertOptions options) {
        final LinkedHashMap<Object, DBObject> involvedObjects = new LinkedHashMap<>();
        dbColl.insert(singletonList(entityToDBObj(entity, involvedObjects)), enforceWriteConcern(options, entity.getClass())
                                                                                 .getOptions());

        return postSaveOperations(singletonList(entity), involvedObjects, dbColl.getName()).get(0);
    }

    private MongoCollection enforceWriteConcern(final MongoCollection collection, final Class klass) {
        WriteConcern applied = getWriteConcern(klass);
        return applied != null
               ? collection.withWriteConcern(applied)
               : collection;
    }

    <T> FindAndModifyOptions enforceWriteConcern(final FindAndModifyOptions options, final Class<T> klass) {
        if (options.getWriteConcern() == null) {
            return options
                       .copy()
                       .writeConcern(getWriteConcern(klass));
        }
        return options;
    }

    <T> InsertOptions enforceWriteConcern(final InsertOptions options, final Class<T> klass) {
        if (options.getWriteConcern() == null) {
            return options
                       .copy()
                       .writeConcern(getWriteConcern(klass));
        }
        return options;
    }

    <T> UpdateOptions enforceWriteConcern(final UpdateOptions options, final Class<T> klass) {
        if (options.getWriteConcern() == null) {
            return options
                       .copy()
                       .writeConcern(getWriteConcern(klass));
        }
        return options;
    }

    <T> DeleteOptions enforceWriteConcern(final DeleteOptions options, final Class<T> klass) {
        if (options.getWriteConcern() == null) {
            return options
                       .copy()
                       .writeConcern(getWriteConcern(klass));
        }
        return options;
    }

    protected <T> Key<T> save(final DBCollection dbColl, final T entity, final InsertOptions options) {
        final MappedClass mc = validateSave(entity);

        // involvedObjects is used not only as a cache but also as a list of what needs to be called for life-cycle methods at the end.
        final LinkedHashMap<Object, DBObject> involvedObjects = new LinkedHashMap<>();
        final DBObject document = entityToDBObj(entity, involvedObjects);

        // try to do an update if there is a @Version field
        final Object idValue = document.get("_id");
        WriteResult wr = tryVersionedUpdate(dbColl, entity, document, idValue, enforceWriteConcern(options, entity.getClass()), mc);

        if (wr == null) {
            saveDocument(dbColl, document, options);
        }

        return postSaveOperations(singletonList(entity), involvedObjects, dbColl.getName()).get(0);
    }

/*
    @SuppressWarnings("unchecked")
    private <T> Key<T> save(final MongoCollection collection, final T entity, final InsertOneOptions options) {
        final MappedClass mc = validateSave(entity);

        // involvedObjects is used not only as a cache but also as a list of what needs to be called for life-cycle methods at the end.
        final LinkedHashMap<Object, DBObject> involvedObjects = new LinkedHashMap<Object, DBObject>();
        final Document document = new Document(entityToDBObj(entity, involvedObjects).toMap());

        // try to do an update if there is a @Version field
        final Object idValue = document.get(Mapper.ID_KEY);
        UpdateResult wr = tryVersionedUpdate(collection, entity, document, idValue, options, mc);

        if (wr == null) {
            if (document.get(ID_FIELD_NAME) == null) {
                 collection.insertOne(singletonList(document), options);
            } else {
                collection.updateOne(new Document(ID_FIELD_NAME, document.get(ID_FIELD_NAME)), document,
                    new com.mongodb.client.model.UpdateOptions()
                        .bypassDocumentValidation(options.getBypassDocumentValidation())
                        .upsert(true));
            }
        }

        return postSaveOperations(singletonList(entity), involvedObjects, collection.getNamespace().getCollectionName()).get(0);
    }
*/

    private <T> MappedClass validateSave(final T entity) {
        if (entity == null) {
            throw new UpdateException("Can not persist a null entity");
        }

        final MappedClass mc = mapper.getMappedClass(entity);
        if (mc.getAnnotation(NotSaved.class) != null) {
            throw new MappingException(format("Entity type: %s is marked as NotSaved which means you should not try to save it!",
                mc.getClazz().getName()));
        }
        return mc;
    }

    private WriteResult saveDocument(final DBCollection dbColl, final DBObject document, final InsertOptions options) {
        if (document.get(ID_FIELD_NAME) == null) {
            return dbColl.insert(singletonList(document), options.getOptions());
        } else {
            return dbColl.update(new BasicDBObject(ID_FIELD_NAME, document.get(ID_FIELD_NAME)), document,
                new DBCollectionUpdateOptions()
                    .bypassDocumentValidation(options.getBypassDocumentValidation())
                    .writeConcern(options.getWriteConcern())
                    .upsert(true));
        }
    }

    private <T> WriteResult tryVersionedUpdate(final DBCollection dbColl, final T entity, final DBObject dbObj, final Object idValue,
                                               final InsertOptions options, final MappedClass mc) {
        WriteResult wr;
        if (mc.getFieldsAnnotatedWith(Version.class).isEmpty()) {
            return null;
        }

        final MappedField mfVersion = mc.getMappedVersionField();
        final String versionKeyName = mfVersion.getNameToStore();

        Long oldVersion = (Long) mfVersion.getFieldValue(entity);
        long newVersion = nextValue(oldVersion);

        final DBObject set = (DBObject) dbObj.get("$set");
        if (set == null) {
            dbObj.put(versionKeyName, newVersion);
        } else {
            set.put(versionKeyName, newVersion);
        }

        if (idValue != null && newVersion == 1) {
            try {
                wr = dbColl.insert(singletonList(dbObj), options.getOptions());
            } catch (DuplicateKeyException e) {
                throw new ConcurrentModificationException(format("Entity of class %s (id='%s') was concurrently saved.",
                    entity.getClass().getName(), idValue));
            }
        } else if (idValue != null) {
            final QueryImpl<?> query = (QueryImpl<?>) find(dbColl.getName())
                                       .disableValidation()
                                       .filter("_id", idValue)
                                       .enableValidation()
                                       .filter(versionKeyName, oldVersion);
            final UpdateResults res = update(query, dbObj, new UpdateOptions()
                                                               .bypassDocumentValidation(options.getBypassDocumentValidation())
                                                               .writeConcern(options.getWriteConcern()));

            wr = res.getWriteResult();

            if (res.getUpdatedCount() != 1) {
                throw new ConcurrentModificationException(format("Entity of class %s (id='%s',version='%d') was concurrently updated.",
                    entity.getClass().getName(), idValue, oldVersion));
            }
        } else {
            wr = saveDocument(dbColl, dbObj, options);
        }

        return wr;
    }

    private EntityCache createCache() {
        return mapper.createEntityCache();
    }

    private DBObject entityToDBObj(final Object entity, final Map<Object, DBObject> involvedObjects) {
        return mapper.toDBObject(ProxyHelper.unwrap(entity), involvedObjects);
    }

    private <T> Iterable<Key<T>> insert(final DBCollection dbColl, final Iterable<T> entities, final InsertOptions options) {
        if (!entities.iterator().hasNext()) {
            return emptyList();
        }

        final Map<Object, DBObject> involvedObjects = new LinkedHashMap<>();
        final List<DBObject> list = new ArrayList<>();
        com.mongodb.InsertOptions insertOptions = options.getOptions();
        for (final T entity : entities) {
            if (options.getWriteConcern() == null) {
                insertOptions = enforceWriteConcern(options, entity.getClass()).getOptions();
            }
            list.add(toDbObject(entity, involvedObjects));
        }
        dbColl.insert(list, insertOptions);

        return postSaveOperations(entities, involvedObjects, dbColl.getName());
    }

    /**
     * Creates and returns a {@link Query} using the underlying {@link QueryFactory}.
     *
     * @see QueryFactory#createQuery(Datastore, DBCollection, Class, DBObject)
     */
    private <T> Query<T> newQuery(final Class<T> type, final DBCollection collection, final DBObject query) {
        return getQueryFactory().createQuery(this, collection, type, query);
    }

    /**
     * Creates and returns a {@link Query} using the underlying {@link QueryFactory}.
     *
     * @see QueryFactory#createQuery(Datastore, DBCollection, Class)
     */
    private <T> Query<T> newQuery(final Class<T> type, final DBCollection collection) {
        return getQueryFactory().createQuery(this, collection, type);
    }

    private long nextValue(final Long oldVersion) {
        return oldVersion == null ? 1 : oldVersion + 1;
    }

    private <T> List<Key<T>> postSaveOperations(final Iterable<T> entities,
                                                final Map<Object, DBObject> involvedObjects,
                                                final String collectionName) {
        return postSaveOperations(entities, involvedObjects, true, collectionName);
    }

    @SuppressWarnings("unchecked")
    private <T> List<Key<T>> postSaveOperations(final Iterable<T> entities, final Map<Object, DBObject> involvedObjects,
                                                final boolean fetchKeys, final String collectionName) {
        List<Key<T>> keys = new ArrayList<>();
        for (final T entity : entities) {
            final DBObject dbObj = involvedObjects.remove(entity);

            if (fetchKeys) {
                if (dbObj.get("_id") == null) {
                    throw new MappingException(format("Missing _id after save on %s", entity.getClass().getName()));
                }
                mapper.updateKeyAndVersionInfo(this, dbObj, createCache(), entity);
                keys.add(new Key<>((Class<? extends T>) entity.getClass(), collectionName, mapper.getId(entity)));
            }
            mapper.getMappedClass(entity).callLifecycleMethods(PostPersist.class, entity, dbObj, mapper);
        }

        for (Entry<Object, DBObject> entry : involvedObjects.entrySet()) {
            final Object key = entry.getKey();
            mapper.getMappedClass(key).callLifecycleMethods(PostPersist.class, key, entry.getValue(), mapper);

        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private <T> Query<T> queryByExample(final DBCollection coll, final T example) {
        // TODO: think about remove className from baseQuery param below.
        final Class<T> type = (Class<T>) example.getClass();
        final DBObject query = entityToDBObj(example, new HashMap<>());
        return newQuery(type, coll, query);
    }

    private <T> DBObject toDbObject(final T ent, final Map<Object, DBObject> involvedObjects) {
        final MappedClass mc = mapper.getMappedClass(ent);
        if (mc.getAnnotation(NotSaved.class) != null) {
            throw new MappingException(format("Entity type: %s is marked as NotSaved which means you should not try to save it!",
                mc.getClazz().getName()));
        }
        DBObject dbObject = entityToDBObj(ent, involvedObjects);
        List<MappedField> versionFields = mc.getFieldsAnnotatedWith(Version.class);
        for (MappedField mappedField : versionFields) {
            String name = mappedField.getNameToStore();
            if (dbObject.get(name) == null) {
                dbObject.put(name, 1);
                mappedField.setFieldValue(ent, 1L);
            }
        }
        return dbObject;
    }

    @Override
    public <T> UpdateResults update(final Query<T> query, final UpdateOperations<T> operations, final UpdateOptions options) {
        final QueryImpl<T> queryImpl = (QueryImpl<T>) query;
        DBCollection dbColl = queryImpl.getCollection();
        // TODO remove this after testing.
        if (dbColl == null) {
            dbColl = getCollection(queryImpl.getEntityClass());
        }

        final MappedClass mc = getMapper().getMappedClass(queryImpl.getEntityClass());
        final List<MappedField> fields = mc.getFieldsAnnotatedWith(Version.class);

        DBObject queryObject = queryImpl.getQueryObject();

        if (!fields.isEmpty()) {
            operations.inc(fields.get(0).getNameToStore(), 1);
        }

        final BasicDBObject update = (BasicDBObject) ((UpdateOpsImpl) operations).getOps();
        if (LOG.isTraceEnabled()) {
            LOG.trace(format("Executing update(%s) for query: %s, ops: %s, multi: %s, upsert: %s",
                dbColl.getName(), queryObject, update, options.isMulti(), options.isUpsert()));
        }

        return new UpdateResults(dbColl.update(queryObject, update,
            enforceWriteConcern(options, queryImpl.getEntityClass())
                .getOptions()));
    }

    @SuppressWarnings("unchecked")
    private <T> UpdateResults update(final QueryImpl<T> query, final DBObject update, final UpdateOptions options) {

        DBCollection dbColl = query.getCollection();
        // TODO remove this after testing.
        if (dbColl == null) {
            dbColl = getCollection(query.getEntityClass());
        }

        if (query.getSortObject() != null && query.getSortObject().keySet() != null && !query.getSortObject().keySet().isEmpty()) {
            throw new QueryException("sorting is not allowed for updates.");
        }
        if (query.getOffset() > 0) {
            throw new QueryException("a query offset is not allowed for updates.");
        }

        DBObject queryObject = query.getQueryObject();

        final MappedClass mc = getMapper().getMappedClass(query.getEntityClass());
        final List<MappedField> fields = mc.getFieldsAnnotatedWith(Version.class);
        if (!fields.isEmpty()) {
            final MappedField versionMF = fields.get(0);
            DBObject localUpdate = update;
            if (localUpdate.get("$set") != null) {
                localUpdate = (DBObject) localUpdate.get("$set");
            }
            if (localUpdate.get(versionMF.getNameToStore()) == null) {
                if (!localUpdate.containsField("$inc")) {
                    localUpdate.put("$inc", new BasicDBObject(versionMF.getNameToStore(), 1));
                } else {
                    ((Map<String, Object>) (localUpdate.get("$inc"))).put(versionMF.getNameToStore(), 1);
                }
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace(format("Executing update(%s) for query: %s, ops: %s, multi: %s, upsert: %s",
                dbColl.getName(), queryObject, update, options.isMulti(), options.isUpsert()));
        }

        return new UpdateResults(dbColl.update(queryObject, update,
            enforceWriteConcern(options, query.getEntityClass())
                .getOptions()));
    }

    /**
     * Gets the write concern for entity or returns the default write concern for this datastore
     *
     * @param clazzOrEntity the class or entity to use when looking up the WriteConcern
     */
    private WriteConcern getWriteConcern(final Object clazzOrEntity) {
        WriteConcern wc = defConcern;
        if (clazzOrEntity != null) {
            final Entity entityAnn = getMapper().getMappedClass(clazzOrEntity).getEntityAnnotation();
            if (entityAnn != null && !entityAnn.concern().isEmpty()) {
                wc = WriteConcern.valueOf(entityAnn.concern());
            }
        }

        return wc;
    }
}