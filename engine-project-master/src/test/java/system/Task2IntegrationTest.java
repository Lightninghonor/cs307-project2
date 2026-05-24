package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.system.TransactionManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class Task2IntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Task2 demo path supports DDL, 30+ rows, projection, filters, delete, count, join, and transaction")
    void task2DemoPathWorksEndToEnd() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE t (id int, name varchar, age int, gpa double)");
        executeStatement(dbManager, "CREATE TABLE d (student_id int, title varchar)");

        for (int i = 1; i <= 35; i++) {
            long age = 18 + (i % 5);
            double gpa = 2.0 + (i / 10.0);
            executeStatement(dbManager,
                    String.format("INSERT INTO t (id, name, age, gpa) VALUES (%d, 'name%d', %d, %.1f)",
                            i, i, age, gpa));
        }
        executeStatement(dbManager, "INSERT INTO d (student_id, title) VALUES (6, 'team6')");
        executeStatement(dbManager, "INSERT INTO d (student_id, title) VALUES (7, 'team7')");
        executeStatement(dbManager, "INSERT INTO d (student_id, title) VALUES (8, 'team8')");

        assertThat(scalarLong(dbManager, "SELECT count(*) FROM t")).isEqualTo(35L);

        List<List<Value>> projected = queryRows(dbManager,
                "SELECT t.id, t.name FROM t WHERE t.age >= 20 AND t.id <= 10");
        assertThat(projected)
                .extracting(row -> row.get(0).value)
                .containsExactly(2L, 3L, 4L, 7L, 8L, 9L);
        assertThat(projected)
                .extracting(row -> row.get(1).value)
                .containsExactly("name2", "name3", "name4", "name7", "name8", "name9");

        List<List<Value>> orFiltered = queryRows(dbManager,
                "SELECT t.id FROM t WHERE t.age < 19 OR t.id = 34");
        assertThat(orFiltered)
                .extracting(row -> row.get(0).value)
                .containsExactly(5L, 10L, 15L, 20L, 25L, 30L, 34L, 35L);

        List<List<Value>> joined = queryRows(dbManager,
                "SELECT t.id, d.title FROM t JOIN d ON t.id = d.student_id WHERE d.student_id <= 8");
        assertThat(joined)
                .extracting(row -> row.get(0).value)
                .containsExactly(6L, 7L, 8L);
        assertThat(joined)
                .extracting(row -> row.get(1).value)
                .containsExactly("team6", "team7", "team8");

        executeStatement(dbManager, "DELETE FROM t WHERE t.id <= 5");
        assertThat(scalarLong(dbManager, "SELECT count(*) FROM t")).isEqualTo(30L);
        assertThat(queryRows(dbManager, "SELECT * FROM t WHERE t.id = 3")).isEmpty();

        executeStatement(dbManager, "BEGIN");
        executeStatement(dbManager, "INSERT INTO t (id, name, age, gpa) VALUES (100, 'tx100', 23, 4.0)");
        executeStatement(dbManager, "SAVEPOINT added_100");
        executeStatement(dbManager, "INSERT INTO t (id, name, age, gpa) VALUES (101, 'tx101', 24, 4.1)");
        executeStatement(dbManager, "ROLLBACK TO SAVEPOINT added_100");
        executeStatement(dbManager, "COMMIT");
        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.id >= 100"))
                .extracting(row -> row.get(0).value)
                .containsExactly(100L);

        assertThatCode(() -> executeStatement(dbManager, "SHOW TABLES")).doesNotThrowAnyException();
        assertThatCode(() -> executeStatement(dbManager, "DESC t")).doesNotThrowAnyException();
        assertThatCode(() -> executeStatement(dbManager, "EXPLAIN SELECT t.id FROM t WHERE t.age > 20"))
                .doesNotThrowAnyException();
        assertThatCode(() -> executeStatement(dbManager, "DROP TABLE d")).doesNotThrowAnyException();
        assertThat(dbManager.isTableExists("d")).isFalse();
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(32, diskManager, replacerFactory.apply(32));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager, null,
                replacerFactory);
        dbManager.setTransactionManager(new TransactionManager(dbManager));
        return dbManager;
    }

    private void executeStatement(DBManager dbManager, String sql) throws DBException {
        queryRows(dbManager, sql);
    }

    private long scalarLong(DBManager dbManager, String sql) throws DBException {
        List<List<Value>> rows = queryRows(dbManager, sql);
        assertThat(rows).hasSize(1);
        return (Long) rows.get(0).get(0).value;
    }

    private List<List<Value>> queryRows(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return List.of();
        }

        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<List<Value>> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
            while (physicalOperator.hasNext()) {
                physicalOperator.Next();
                Tuple tuple = physicalOperator.Current();
                if (tuple != null) {
                    rows.add(List.of(tuple.getValues()));
                }
            }
        } finally {
            physicalOperator.Close();
            dbManager.getBufferPool().FlushAllPages("");
        }
        return rows;
    }
}
