package edu.sustech.cs307.meta;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TableMeta {
    public String tableName;
    public ArrayList<ColumnMeta> columns_list;

    @JsonIgnore
    public Map<String, ColumnMeta> columns; // 列名 -> 列的元数据

    private Map<String, IndexType> indexes; // 索引信息
    private Map<String, String> indexColumns; // index name -> column name

    private Map<String, Integer> column_rank;

    public enum IndexType {
        BTREE
    }

    public TableMeta(String tableName) {
        this.tableName = tableName;
        this.columns = new HashMap<>();
        this.indexes = new HashMap<>();
        this.indexColumns = new HashMap<>();
    }

    public TableMeta(String tableName, ArrayList<ColumnMeta> columns) {
        this.tableName = tableName;
        this.columns_list = columns;
        this.columns = new HashMap<>();
        this.indexes = new HashMap<>();
        this.indexColumns = new HashMap<>();
        for (ColumnMeta column : columns) {
            this.columns.put(column.name, column);
        }
    }

    @JsonCreator
    public TableMeta(@JsonProperty("tableName") String tableName,
                     @JsonProperty("columns_list") ArrayList<ColumnMeta> columns_list,
                     @JsonProperty("indexes") Map<String, IndexType> indexes,
                     @JsonProperty("indexColumns") Map<String, String> indexColumns) {
        this.tableName = tableName;
        this.columns_list = columns_list;
        this.columns = new HashMap<>();
        this.indexes = indexes == null ? new HashMap<>() : indexes;
        this.indexColumns = indexColumns == null ? new HashMap<>() : indexColumns;
        for (var column : columns_list) {
            this.columns.put(column.name, column);
        }
    }

    public void addColumn(ColumnMeta column) throws DBException {
        String columnName = column.name;
        if (this.columns.containsKey(columnName)) {
            throw new DBException(ExceptionTypes.ColumnAlreadyExist(columnName));
        }
        this.columns.put(columnName, column);
        if (this.columns_list == null) {
            this.columns_list = new ArrayList<>();
        }
        this.columns_list.add(column);
    }

    public void dropColumn(String columnName) throws DBException {
        if (!this.columns.containsKey(columnName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }
        this.columns.remove(columnName);
        if (this.columns_list != null) {
            this.columns_list.removeIf(column -> column.name.equals(columnName));
        }
        getIndexColumns().entrySet().removeIf(entry -> entry.getValue().equalsIgnoreCase(columnName));
        getIndexes().keySet().removeIf(indexName -> !getIndexColumns().containsKey(indexName));
    }

    public ColumnMeta getColumnMeta(String columnName) {
        if (this.columns.containsKey(columnName)) {
            return this.columns.get(columnName);
        }
        return null;
    }

    public Map<String, ColumnMeta> getColumns() {
        return this.columns;
    }

    public void setColumns(Map<String, ColumnMeta> columns) {
        this.columns = columns;
    }

    public int columnCount() {
        return this.columns.size();
    }

    public boolean hasColumn(String columnName) {
        return this.columns.containsKey(columnName);
    }

    public Map<String, IndexType> getIndexes() {
        if (indexes == null) {
            indexes = new HashMap<>();
        }
        return indexes;
    }

    public void setIndexes(Map<String, IndexType> indexes) {
        this.indexes = indexes;
    }

    public Map<String, String> getIndexColumns() {
        if (indexColumns == null) {
            indexColumns = new HashMap<>();
        }
        return indexColumns;
    }

    public void addIndex(String indexName, String columnName) throws DBException {
        if (!hasColumn(columnName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }
        if (getIndexes().containsKey(indexName)) {
            throw new DBException(ExceptionTypes.InvalidSQL(indexName, "Index already exists"));
        }
        getIndexes().put(indexName, IndexType.BTREE);
        getIndexColumns().put(indexName, columnName);
    }

    public void dropIndex(String indexName) throws DBException {
        if (!getIndexes().containsKey(indexName)) {
            throw new DBException(ExceptionTypes.InvalidSQL(indexName, "Index does not exist"));
        }
        getIndexes().remove(indexName);
        getIndexColumns().remove(indexName);
    }

    public String getIndexColumn(String indexName) {
        return getIndexColumns().get(indexName);
    }

    public String getIndexNameOnColumn(String columnName) {
        for (Map.Entry<String, String> entry : getIndexColumns().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(columnName)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
