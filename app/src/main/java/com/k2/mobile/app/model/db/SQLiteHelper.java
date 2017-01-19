/*
 * Copyright (c) 2015. OPPO Co., Ltd.
 */
package com.k2.mobile.app.model.db;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import com.k2.mobile.app.common.exception.DbException;
import com.k2.mobile.app.model.db.sqlite.CursorUtils;
import com.k2.mobile.app.model.db.sqlite.DbModelSelector;
import com.k2.mobile.app.model.db.sqlite.Selector;
import com.k2.mobile.app.model.db.sqlite.SqlInfo;
import com.k2.mobile.app.model.db.sqlite.SqlInfoBuilder;
import com.k2.mobile.app.model.db.sqlite.WhereBuilder;
import com.k2.mobile.app.model.db.table.DbModel;
import com.k2.mobile.app.model.db.table.Id;
import com.k2.mobile.app.model.db.table.Table;
import com.k2.mobile.app.model.db.table.TableUtils;
import com.k2.mobile.app.utils.IOUtil;
import com.k2.mobile.app.utils.LogUtil;

public class SQLiteHelper {

    //*************************************** create instance ****************************************************

    /**
     * key: dbName
     */
    private static HashMap<String, SQLiteHelper> daoMap = new HashMap<String, SQLiteHelper>();

    private SQLiteDatabase database;
    private DaoConfig daoConfig;
    private boolean debug = false;
    private boolean allowTransaction = false;

    private SQLiteHelper(DaoConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("daoConfig may not be null");
        }
        this.database = createDatabase(config);
        this.daoConfig = config;
    }


    private synchronized static SQLiteHelper getInstance(DaoConfig daoConfig) {
        SQLiteHelper dao = daoMap.get(daoConfig.getDbName());
        if (dao == null) {
            dao = new SQLiteHelper(daoConfig);
            daoMap.put(daoConfig.getDbName(), dao);
        } else {
            dao.daoConfig = daoConfig;
        }

        // update the database if needed
        SQLiteDatabase database = dao.database;
        int oldVersion = database.getVersion();
        int newVersion = daoConfig.getDbVersion();
        if (oldVersion != newVersion) {
            if (oldVersion != 0) {
                DbUpgradeListener upgradeListener = daoConfig.getDbUpgradeListener();
                if (upgradeListener != null) {
                    upgradeListener.onUpgrade(dao, oldVersion, newVersion);
                } else {
                    try {
                        dao.dropDb();
                    } catch (DbException e) {
                        LogUtil.e(e.getMessage(), e);
                    	throw new DbException(e);
                    }
                }
            }
            database.setVersion(newVersion);
        }

        return dao;
    }

    public static SQLiteHelper create(Context context) {
        DaoConfig config = new DaoConfig(context);
        return getInstance(config);
    }

    public static SQLiteHelper create(Context context, String dbName) {
        DaoConfig config = new DaoConfig(context);
        config.setDbName(dbName);
        return getInstance(config);
    }

    public static SQLiteHelper create(Context context, String dbDir, String dbName) {
        DaoConfig config = new DaoConfig(context);
        config.setDbDir(dbDir);
        config.setDbName(dbName);
        return getInstance(config);
    }

    public static SQLiteHelper create(Context context, String dbName, int dbVersion, DbUpgradeListener dbUpgradeListener) {
        DaoConfig config = new DaoConfig(context);
        config.setDbName(dbName);
        config.setDbVersion(dbVersion);
        config.setDbUpgradeListener(dbUpgradeListener);
        return getInstance(config);
    }

    public static SQLiteHelper create(Context context, String dbDir, String dbName, int dbVersion, DbUpgradeListener dbUpgradeListener) {
        DaoConfig config = new DaoConfig(context);
        config.setDbDir(dbDir);
        config.setDbName(dbName);
        config.setDbVersion(dbVersion);
        config.setDbUpgradeListener(dbUpgradeListener);
        return getInstance(config);
    }

    public static SQLiteHelper create(DaoConfig daoConfig) {
        return getInstance(daoConfig);
    }

    public SQLiteHelper configDebug(boolean debug) {
        this.debug = debug;
        return this;
    }

    public SQLiteHelper configAllowTransaction(boolean allowTransaction) {
        this.allowTransaction = allowTransaction;
        return this;
    }

    public SQLiteDatabase getDatabase() {
        return database;
    }

    public DaoConfig getDaoConfig() {
        return daoConfig;
    }

    //*********************************************** operations ********************************************************

    public void saveOrUpdate(Object entity) throws DbException {
        try {
            beginTransaction();

            createTableIfNotExist(entity.getClass());
            saveOrUpdateWithoutTransaction(entity);

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void saveOrUpdateAll(List<?> entities) throws DbException {
        if (entities == null || entities.size() == 0) return;
        try {
            beginTransaction();

            createTableIfNotExist(entities.get(0).getClass());
            for (Object entity : entities) {
                saveOrUpdateWithoutTransaction(entity);
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void replace(Object entity) throws DbException {
        try {
            beginTransaction();

            createTableIfNotExist(entity.getClass());
            execNonQuery(SqlInfoBuilder.buildReplaceSqlInfo(this, entity));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void replaceAll(List<?> entities) throws DbException {
        if (entities == null || entities.size() == 0) return;
        try {
            beginTransaction();

            createTableIfNotExist(entities.get(0).getClass());
            for (Object entity : entities) {
                execNonQuery(SqlInfoBuilder.buildReplaceSqlInfo(this, entity));
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void save(Object entity) throws DbException {
        try {
            beginTransaction();

            createTableIfNotExist(entity.getClass());
            execNonQuery(SqlInfoBuilder.buildInsertSqlInfo(this, entity));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void saveAll(List<?> entities) throws DbException {
        if (entities == null || entities.size() == 0) return;
        try {
            beginTransaction();

            createTableIfNotExist(entities.get(0).getClass());
            for (Object entity : entities) {
                execNonQuery(SqlInfoBuilder.buildInsertSqlInfo(this, entity));
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public boolean saveBindingId(Object entity) throws DbException {
        boolean result = false;
        try {
            beginTransaction();

            createTableIfNotExist(entity.getClass());
            result = saveBindingIdWithoutTransaction(entity);

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
        return result;
    }

    public void saveBindingIdAll(List<?> entities) throws DbException {
        if (entities == null || entities.size() == 0) return;
        try {
            beginTransaction();

            createTableIfNotExist(entities.get(0).getClass());
            for (Object entity : entities) {
                if (!saveBindingIdWithoutTransaction(entity)) {
                    throw new DbException("saveBindingId error, transaction will not commit!");
                }
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void deleteById(Class<?> entityType, Object idValue) throws DbException {
        if (!tableIsExist(entityType)) return;
        try {
            beginTransaction();

            execNonQuery(SqlInfoBuilder.buildDeleteSqlInfo(this, entityType, idValue));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void delete(Object entity) throws DbException {
        if (!tableIsExist(entity.getClass())) return;
        try {
            beginTransaction();

            execNonQuery(SqlInfoBuilder.buildDeleteSqlInfo(this, entity));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void delete(Class<?> entityType, WhereBuilder whereBuilder) throws DbException {
        if (!tableIsExist(entityType)) return;
        try {
            beginTransaction();

            execNonQuery(SqlInfoBuilder.buildDeleteSqlInfo(this, entityType, whereBuilder));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void deleteAll(List<?> entities) throws DbException {
        if (entities == null || entities.size() == 0 || !tableIsExist(entities.get(0).getClass())) return;
        try {
            beginTransaction();

            for (Object entity : entities) {
                execNonQuery(SqlInfoBuilder.buildDeleteSqlInfo(this, entity));
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void deleteAll(Class<?> entityType) throws DbException {
        delete(entityType, null);
    }

    public void update(Object entity, String... updateColumnNames) throws DbException {
        if (!tableIsExist(entity.getClass())) return;
        try {
            beginTransaction();

            execNonQuery(SqlInfoBuilder.buildUpdateSqlInfo(this, entity, updateColumnNames));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void update(Object entity, WhereBuilder whereBuilder, String... updateColumnNames) throws DbException {
        if (!tableIsExist(entity.getClass())) return;
        try {
            beginTransaction();

            execNonQuery(SqlInfoBuilder.buildUpdateSqlInfo(this, entity, whereBuilder, updateColumnNames));

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void updateAll(List<?> entities, String... updateColumnNames) throws DbException {
        if (entities == null || entities.size() == 0 || !tableIsExist(entities.get(0).getClass())) return;
        try {
            beginTransaction();

            for (Object entity : entities) {
                execNonQuery(SqlInfoBuilder.buildUpdateSqlInfo(this, entity, updateColumnNames));
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    public void updateAll(List<?> entities, WhereBuilder whereBuilder, String... updateColumnNames) throws DbException {
        if (entities == null || entities.size() == 0 || !tableIsExist(entities.get(0).getClass())) return;
        try {
            beginTransaction();

            for (Object entity : entities) {
                execNonQuery(SqlInfoBuilder.buildUpdateSqlInfo(this, entity, whereBuilder, updateColumnNames));
            }

            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T findById(Class<T> entityType, Object idValue) throws DbException {
        if (!tableIsExist(entityType)) return null;

        Table table = Table.get(this, entityType);
        Selector selector = Selector.from(entityType).where(table.id.getColumnName(), "=", idValue);

        String sql = selector.limit(1).toString();
        long seq = CursorUtils.FindCacheSequence.getSeq();
        findTempCache.setSeq(seq);
        Object obj = findTempCache.get(sql);
        if (obj != null) {
            return (T) obj;
        }

        Cursor cursor = execQuery(sql);
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    T entity = CursorUtils.getEntity(this, cursor, entityType, seq);
                    findTempCache.put(sql, entity);
                    return entity;
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T findFirst(Selector selector) throws DbException {
        if (!tableIsExist(selector.getEntityType())) return null;

        String sql = selector.limit(1).toString();
        long seq = CursorUtils.FindCacheSequence.getSeq();
        findTempCache.setSeq(seq);
        Object obj = findTempCache.get(sql);
        if (obj != null) {
            return (T) obj;
        }

        Cursor cursor = execQuery(sql);
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    T entity = (T) CursorUtils.getEntity(this, cursor, selector.getEntityType(), seq);
                    findTempCache.put(sql, entity);
                    return entity;
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return null;
    }

    public <T> T findFirst(Class<T> entityType) throws DbException {
        return findFirst(Selector.from(entityType));
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Selector selector) throws DbException {
        if (!tableIsExist(selector.getEntityType())) return null;

        String sql = selector.toString();
        long seq = CursorUtils.FindCacheSequence.getSeq();
        findTempCache.setSeq(seq);
        Object obj = findTempCache.get(sql);
        if (obj != null) {
            return (List<T>) obj;
        }

        List<T> result = new ArrayList<T>();

        Cursor cursor = execQuery(sql);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    T entity = (T) CursorUtils.getEntity(this, cursor, selector.getEntityType(), seq);
                    result.add(entity);
                }
                findTempCache.put(sql, result);
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return result;
    }

    public <T> List<T> findAll(Class<T> entityType) throws DbException {
        return findAll(Selector.from(entityType));
    }

    public DbModel findDbModelFirst(SqlInfo sqlInfo) throws DbException {
        Cursor cursor = execQuery(sqlInfo);
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    return CursorUtils.getDbModel(cursor);
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return null;
    }

    public DbModel findDbModelFirst(DbModelSelector selector) throws DbException {
        if (!tableIsExist(selector.getEntityType())) return null;

        Cursor cursor = execQuery(selector.limit(1).toString());
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    return CursorUtils.getDbModel(cursor);
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return null;
    }

    public List<DbModel> findDbModelAll(SqlInfo sqlInfo) throws DbException {
        List<DbModel> dbModelList = new ArrayList<DbModel>();

        Cursor cursor = execQuery(sqlInfo);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    dbModelList.add(CursorUtils.getDbModel(cursor));
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return dbModelList;
    }

    public List<DbModel> findDbModelAll(DbModelSelector selector) throws DbException {
        if (!tableIsExist(selector.getEntityType())) return null;

        List<DbModel> dbModelList = new ArrayList<DbModel>();

        Cursor cursor = execQuery(selector.toString());
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    dbModelList.add(CursorUtils.getDbModel(cursor));
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return dbModelList;
    }

    public long count(Selector selector) throws DbException {
        Class<?> entityType = selector.getEntityType();
        if (!tableIsExist(entityType)) return 0;

        Table table = Table.get(this, entityType);
        DbModelSelector dmSelector = selector.select("count(" + table.id.getColumnName() + ") as count");
        return findDbModelFirst(dmSelector).getLong("count");
    }

    public long count(Class<?> entityType) throws DbException {
        return count(Selector.from(entityType));
    }

    //******************************************** config ******************************************************

    public static class DaoConfig {
        private Context context;
        private String dbName = "oppo_mo.db"; // default db name
        private int dbVersion = 1;
        private DbUpgradeListener dbUpgradeListener;

        private String dbDir;

        public DaoConfig(Context context) {
            this.context = context.getApplicationContext();
        }

        public Context getContext() {
            return context;
        }

        public String getDbName() {
            return dbName;
        }

        public void setDbName(String dbName) {
            if (!TextUtils.isEmpty(dbName)) {
                this.dbName = dbName;
            }
        }

        public int getDbVersion() {
            return dbVersion;
        }

        public void setDbVersion(int dbVersion) {
            this.dbVersion = dbVersion;
        }

        public DbUpgradeListener getDbUpgradeListener() {
            return dbUpgradeListener;
        }

        public void setDbUpgradeListener(DbUpgradeListener dbUpgradeListener) {
            this.dbUpgradeListener = dbUpgradeListener;
        }

        public String getDbDir() {
            return dbDir;
        }

        /**
         * set database dir
         *
         * @param dbDir If dbDir is null or empty, use the app default db dir.
         */
        public void setDbDir(String dbDir) {
            this.dbDir = dbDir;
        }
    }

    public interface DbUpgradeListener {
        public void onUpgrade(SQLiteHelper db, int oldVersion, int newVersion);
    }

    private SQLiteDatabase createDatabase(DaoConfig config) {
        SQLiteDatabase result = null;

        String dbDir = config.getDbDir();
        if (!TextUtils.isEmpty(dbDir)) {
            File dir = new File(dbDir);
            if (dir.exists() || dir.mkdirs()) {
                File dbFile = new File(dbDir, config.getDbName());
                result = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            }
        } else {
            result = config.getContext().openOrCreateDatabase(config.getDbName(), 0, null);
        }
        return result;
    }

    //***************************** private operations with out transaction *****************************
    private void saveOrUpdateWithoutTransaction(Object entity) throws DbException {
        Table table = Table.get(this, entity.getClass());
        Id id = table.id;
        if (id.isAutoIncrement()) {
            if (id.getColumnValue(entity) != null) {
                execNonQuery(SqlInfoBuilder.buildUpdateSqlInfo(this, entity));
            } else {
                saveBindingIdWithoutTransaction(entity);
            }
        } else {
            execNonQuery(SqlInfoBuilder.buildReplaceSqlInfo(this, entity));
        }
    }

    private boolean saveBindingIdWithoutTransaction(Object entity) throws DbException {
        Class<?> entityType = entity.getClass();
        Table table = Table.get(this, entityType);
        Id idColumn = table.id;
        if (idColumn.isAutoIncrement()) {
            execNonQuery(SqlInfoBuilder.buildInsertSqlInfo(this, entity));
            long id = getLastAutoIncrementId(table.tableName);
            if (id == -1) {
                return false;
            }
            idColumn.setAutoIncrementId(entity, id);
            return true;
        } else {
            execNonQuery(SqlInfoBuilder.buildInsertSqlInfo(this, entity));
            return true;
        }
    }

    //************************************************ tools ***********************************

    private long getLastAutoIncrementId(String tableName) throws DbException {
        long id = -1;
        Cursor cursor = execQuery("SELECT seq FROM sqlite_sequence WHERE name='" + tableName + "'");
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    id = cursor.getLong(0);
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
        return id;
    }

    public void createTableIfNotExist(Class<?> entityType) throws DbException {
        if (!tableIsExist(entityType)) {
            SqlInfo sqlInfo = SqlInfoBuilder.buildCreateTableSqlInfo(this, entityType);
            execNonQuery(sqlInfo);
            String execAfterTableCreated = TableUtils.getExecAfterTableCreated(entityType);
            if (!TextUtils.isEmpty(execAfterTableCreated)) {
                execNonQuery(execAfterTableCreated);
            }
        }
    }

    public boolean tableIsExist(Class<?> entityType) throws DbException {
        Table table = Table.get(this, entityType);
        if (table.isCheckedDatabase()) {
            return true;
        }

        Cursor cursor = execQuery("SELECT COUNT(*) AS c FROM sqlite_master WHERE type='table' AND name='" + table.tableName + "'");
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    int count = cursor.getInt(0);
                    if (count > 0) {
                        table.setCheckedDatabase(true);
                        return true;
                    }
                }
            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }

        return false;
    }

    public void dropDb() throws DbException {
        Cursor cursor = execQuery("SELECT name FROM sqlite_master WHERE type='table' AND name<>'sqlite_sequence'");
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    try {
                        String tableName = cursor.getString(0);
                        execNonQuery("DROP TABLE " + tableName);
                        Table.remove(this, tableName);
                    } catch (Throwable e) {
                        LogUtil.e(e.getMessage(), e);
                    }
                }

            } catch (Throwable e) {
                throw new DbException(e);
            } finally {
                IOUtil.closeQuietly(cursor);
            }
        }
    }

    public void dropTable(Class<?> entityType) throws DbException {
        if (!tableIsExist(entityType)) return;
        String tableName = TableUtils.getTableName(entityType);
        execNonQuery("DROP TABLE " + tableName);
        Table.remove(this, entityType);
    }

    public void close() {
        String dbName = this.daoConfig.getDbName();
        if (daoMap.containsKey(dbName)) {
            daoMap.remove(dbName);
            this.database.close();
        }
    }

    ///////////////////////////////////// exec sql /////////////////////////////////////////////////////
    private void debugSql(String sql) {
        if (debug) {
            LogUtil.d(sql);
        }
    }

    private Lock writeLock = new ReentrantLock();
    private volatile boolean writeLocked = false;

    private void beginTransaction() {
        if (allowTransaction) {
            database.beginTransaction();
        } else {
            writeLock.lock();
            writeLocked = true;
        }
    }

    private void setTransactionSuccessful() {
        if (allowTransaction) {
            database.setTransactionSuccessful();
        }
    }

    private void endTransaction() {
        if (allowTransaction) {
            database.endTransaction();
        }
        if (writeLocked) {
            writeLock.unlock();
            writeLocked = false;
        }
    }


    public void execNonQuery(SqlInfo sqlInfo) throws DbException {
        debugSql(sqlInfo.getSql());
        try {
            if (sqlInfo.getBindArgs() != null) {
                database.execSQL(sqlInfo.getSql(), sqlInfo.getBindArgsAsArray());
            } else {
                database.execSQL(sqlInfo.getSql());
            }
        } catch (Throwable e) {
            throw new DbException(e);
        }
    }

    public void execNonQuery(String sql) throws DbException {
        debugSql(sql);
        try {
            database.execSQL(sql);
        } catch (Throwable e) {
            throw new DbException(e);
        }
    }

    public Cursor execQuery(SqlInfo sqlInfo) throws DbException {
        debugSql(sqlInfo.getSql());
        try {
            return database.rawQuery(sqlInfo.getSql(), sqlInfo.getBindArgsAsStrArray());
        } catch (Throwable e) {
            throw new DbException(e);
        }
    }

    public Cursor execQuery(String sql) throws DbException {
        debugSql(sql);
        try {
            return database.rawQuery(sql, null);
        } catch (Throwable e) {
            throw new DbException(e);
        }
    }

    /////////////////////// temp cache ////////////////////////////////////////////////////////////////
    private final FindTempCache findTempCache = new FindTempCache();

    private class FindTempCache {
        private FindTempCache() {
        }

        /**
         * key: sql;
         * value: find result
         */
        private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<String, Object>();

        private long seq = 0;

        public void put(String sql, Object result) {
            if (sql != null && result != null) {
                cache.put(sql, result);
            }
        }

        public Object get(String sql) {
            return cache.get(sql);
        }

        public void setSeq(long seq) {
            if (this.seq != seq) {
                cache.clear();
                this.seq = seq;
            }
        }
    }

}
