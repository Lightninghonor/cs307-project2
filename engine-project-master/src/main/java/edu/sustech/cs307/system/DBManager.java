package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.physicalOperator.SeqScanOperator;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;
    private final Map<String, Map<String, BPlusTreeIndex>> runtimeIndexes = new HashMap<>();

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    public void showTables() {
        Logger.info("|-----------|");
        Logger.info("|  Tables   |");
        Logger.info("|-----------|");
        for (String tableName : metaManager.getTableNames()) {
            Logger.info("|{}|", StringUtils.center(tableName, 11, ' '));
        }
        Logger.info("|-----------|");
    }

    public void descTable(String table_name) throws DBException {
        TableMeta tableMeta = metaManager.getTable(table_name);
        Logger.info("|-------------------------|");
        Logger.info("|{}|{}|", StringUtils.center("Field", 12, ' '), StringUtils.center("Type", 12, ' '));
        Logger.info("|-------------------------|");
        for (ColumnMeta column : tableMeta.columns_list) {
            Logger.info("|{}|{}|", StringUtils.center(column.name, 12, ' '), StringUtils.center(column.type.toString(), 12, ' '));
        }
        Logger.info("|-------------------------|");
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
    public void dropTable(String table_name) throws DBException {
        if (!isTableExists(table_name)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(table_name));
        }
        String dataFile = String.format("%s/%s", table_name, "data");
        bufferPool.FlushAllPages(dataFile);
        bufferPool.DeleteAllPages(dataFile);
        recordManager.DeleteFile(dataFile);

        File tableDir = new File(String.format("%s/%s", diskManager.getCurrentDir(), table_name));
        if (tableDir.exists()) {
            deleteDirectory(tableDir);
        }

        metaManager.dropTable(table_name);
        runtimeIndexes.remove(table_name);
        DiskManager.dump_disk_manager_meta(diskManager);
        Logger.info("Successfully dropped table: {}", table_name);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages(null);
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void createIndex(String indexName, String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        tableMeta.addIndex(indexName, columnName);
        metaManager.saveToJson();
        rebuildIndex(tableName, indexName);
        Logger.info("Successfully created index {} on {}({})", indexName, tableName, columnName);
    }

    public void dropIndex(String indexName) throws DBException {
        String tableName = metaManager.findTableByIndexName(indexName);
        if (tableName == null) {
            throw new DBException(ExceptionTypes.InvalidSQL(indexName, "Index does not exist"));
        }
        dropIndex(indexName, tableName);
    }

    public void dropIndex(String indexName, String tableName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        tableMeta.dropIndex(indexName);
        Map<String, BPlusTreeIndex> tableIndexes = runtimeIndexes.get(tableName);
        if (tableIndexes != null) {
            tableIndexes.remove(indexName);
        }
        metaManager.saveToJson();
        Logger.info("Successfully dropped index {} on {}", indexName, tableName);
    }

    public List<RID> lookupIndex(String tableName, String columnName, String operator, Value value) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        String indexName = tableMeta.getIndexNameOnColumn(columnName);
        if (indexName == null) {
            return null;
        }
        BPlusTreeIndex index = getRuntimeIndex(tableName, indexName);
        return switch (operator) {
            case "=" -> index.equalToRids(value);
            case ">" -> index.moreThanRids(value, false);
            case ">=" -> index.moreThanRids(value, true);
            case "<" -> index.lessThanRids(value, false);
            case "<=" -> index.lessThanRids(value, true);
            default -> null;
        };
    }

    public void addRecordToIndexes(String tableName, List<Value> rowValues, RID rid) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexes().isEmpty()) {
            return;
        }
        ensureRuntimeIndexes(tableName);
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            Value key = valueByColumn(tableMeta, rowValues, entry.getValue());
            runtimeIndexes.get(tableName).get(entry.getKey()).insert(key, rid);
        }
    }

    public void removeRecordFromIndexes(String tableName, TableTuple tuple) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexes().isEmpty()) {
            return;
        }
        ensureRuntimeIndexes(tableName);
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            Value key = tuple.getValue(new TabCol(tableName, entry.getValue()));
            runtimeIndexes.get(tableName).get(entry.getKey()).delete(key, tuple.getRID());
        }
    }

    public void updateRecordIndexes(String tableName, List<Value> oldValues, List<Value> newValues, RID rid)
            throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexes().isEmpty()) {
            return;
        }
        ensureRuntimeIndexes(tableName);
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            BPlusTreeIndex index = runtimeIndexes.get(tableName).get(entry.getKey());
            String columnName = entry.getValue();
            index.delete(valueByColumn(tableMeta, oldValues, columnName), rid);
            index.insert(valueByColumn(tableMeta, newValues, columnName), rid);
        }
    }

    public void printIndex(String indexName) throws DBException {
        String tableName = metaManager.findTableByIndexName(indexName);
        if (tableName == null) {
            throw new DBException(ExceptionTypes.InvalidSQL(indexName, "Index does not exist"));
        }
        Logger.info(getRuntimeIndex(tableName, indexName).printTree());
    }

    public void clearRuntimeIndexes() {
        runtimeIndexes.clear();
    }

    public void alterTableAddColumn(String tableName, String columnName, String dataType) throws DBException {
        if (!isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        if (!isTableEmpty(tableName)) {
            throw new DBException(ExceptionTypes.InvalidSQL(
                    "ALTER TABLE " + tableName,
                    "ADD COLUMN is supported only on empty tables in this implementation"));
        }

        TableMeta tableMeta = metaManager.getTable(tableName);
        int offset = recordSize(tableMeta.columns_list);
        ColumnMeta column = columnMetaFromType(tableName, columnName, dataType, offset);
        metaManager.addColumnInTable(tableName, column);
        recreateEmptyDataFile(tableName, recordSize(metaManager.getTable(tableName).columns_list));
        clearRuntimeIndexes();
        Logger.info("Successfully added column {} to table {}", columnName, tableName);
    }

    public void alterTableDropColumn(String tableName, String columnName) throws DBException {
        if (!isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        if (!isTableEmpty(tableName)) {
            throw new DBException(ExceptionTypes.InvalidSQL(
                    "ALTER TABLE " + tableName,
                    "DROP COLUMN is supported only on empty tables in this implementation"));
        }

        if (metaManager.getTable(tableName).columns_list.size() <= 1) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(tableName));
        }
        metaManager.dropColumnInTable(tableName, columnName);
        TableMeta tableMeta = metaManager.getTable(tableName);
        normalizeColumnOffsets(tableMeta);
        metaManager.saveToJson();
        recreateEmptyDataFile(tableName, recordSize(tableMeta.columns_list));
        clearRuntimeIndexes();
        Logger.info("Successfully dropped column {} from table {}", columnName, tableName);
    }

    private void ensureRuntimeIndexes(String tableName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        Map<String, BPlusTreeIndex> tableIndexes = runtimeIndexes.computeIfAbsent(tableName, key -> new HashMap<>());
        for (String indexName : tableMeta.getIndexes().keySet()) {
            if (!tableIndexes.containsKey(indexName)) {
                rebuildIndex(tableName, indexName);
            }
        }
    }

    private BPlusTreeIndex getRuntimeIndex(String tableName, String indexName) throws DBException {
        ensureRuntimeIndexes(tableName);
        return runtimeIndexes.getOrDefault(tableName, Map.of()).get(indexName);
    }

    private void rebuildIndex(String tableName, String indexName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        String columnName = tableMeta.getIndexColumn(indexName);
        if (columnName == null) {
            throw new DBException(ExceptionTypes.InvalidSQL(indexName, "Index column metadata is missing"));
        }

        BPlusTreeIndex index = new BPlusTreeIndex();
        SeqScanOperator scanner = new SeqScanOperator(tableName, this);
        scanner.Begin();
        try {
            while (scanner.hasNext()) {
                scanner.Next();
                TableTuple tuple = (TableTuple) scanner.Current();
                if (tuple != null) {
                    index.insert(tuple.getValue(new TabCol(tableName, columnName)), tuple.getRID());
                }
            }
        } finally {
            scanner.Close();
        }
        runtimeIndexes.computeIfAbsent(tableName, key -> new HashMap<>()).put(indexName, index);
    }

    private Value valueByColumn(TableMeta tableMeta, List<Value> rowValues, String columnName) throws DBException {
        for (int i = 0; i < tableMeta.columns_list.size(); i++) {
            ColumnMeta columnMeta = tableMeta.columns_list.get(i);
            if (columnMeta.name.equalsIgnoreCase(columnName)) {
                return rowValues.get(i);
            }
        }
        throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
    }

    private boolean isTableEmpty(String tableName) throws DBException {
        SeqScanOperator scanner = new SeqScanOperator(tableName, this);
        scanner.Begin();
        try {
            return !scanner.hasNext();
        } finally {
            scanner.Close();
        }
    }

    private ColumnMeta columnMetaFromType(String tableName, String columnName, String dataType, int offset)
            throws DBException {
        if (columnName.isEmpty() || columnName.length() > 10) {
            throw new DBException(ExceptionTypes.InvalidSQL(
                    "ALTER TABLE " + tableName, "INVALID COLUMN NAME = " + columnName));
        }
        if (dataType.equalsIgnoreCase("char") || dataType.equalsIgnoreCase("varchar")) {
            return new ColumnMeta(tableName, columnName, ValueType.CHAR, Value.CHAR_SIZE, offset);
        }
        if (dataType.equalsIgnoreCase("int") || dataType.equalsIgnoreCase("integer")) {
            return new ColumnMeta(tableName, columnName, ValueType.INTEGER, Value.INT_SIZE, offset);
        }
        if (dataType.equalsIgnoreCase("float") || dataType.equalsIgnoreCase("double")) {
            return new ColumnMeta(tableName, columnName, ValueType.FLOAT, Value.FLOAT_SIZE, offset);
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE " + tableName));
    }

    private void recreateEmptyDataFile(String tableName, int recordSize) throws DBException {
        String dataFile = String.format("%s/%s", tableName, "data");
        bufferPool.FlushAllPages(dataFile);
        bufferPool.DeleteAllPages(dataFile);
        bufferPool.ClearCache();
        recordManager.DeleteFile(dataFile);
        recordManager.CreateFile(dataFile, recordSize);
        bufferPool.ClearCache();
    }

    private int recordSize(List<ColumnMeta> columns) {
        int recordSize = 0;
        for (ColumnMeta column : columns) {
            recordSize += column.len;
        }
        return recordSize;
    }

    private void normalizeColumnOffsets(TableMeta tableMeta) {
        int offset = 0;
        for (ColumnMeta column : tableMeta.columns_list) {
            column.offset = offset;
            offset += column.len;
        }
    }
}
