package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;


public class TransactionManager {

    private final DBManager dbManager;
    private Path transactionSnapshot;
    private final List<SavepointSnapshot> savepoints = new ArrayList<>();

    private static class SavepointSnapshot {
        private final String name;
        private final Path snapshot;

        private SavepointSnapshot(String name, Path snapshot) {
            this.name = name;
            this.snapshot = snapshot;
        }
    }


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }


    public void begin() throws DBException {
        if (isActive()) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        savepoints.clear();
    }


    public void commit() throws DBException {
        if (!isActive()) {
            return;
        }
        dbManager.persistRuntimeState();
        clearTransactionState();
    }


    public void rollback() throws DBException {
        if (!isActive()) {
            return;
        }
        restoreSnapshot(transactionSnapshot);
        clearTransactionState();
    }


    public void savepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        savepoints.add(new SavepointSnapshot(savepointName, createSnapshot()));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index == -1) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        SavepointSnapshot target = savepoints.get(index);
        restoreSnapshot(target.snapshot);
        removeSavepointsAfter(index);
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index == -1) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        deleteDirectory(savepoints.remove(index).snapshot);
    }

    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return snapshotDir;
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private boolean isActive() {
        return transactionSnapshot != null;
    }

    private void requireActiveTransaction() throws DBException {
        if (!isActive()) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
    }

    private int findLatestSavepoint(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }

    private void removeSavepointsAfter(int index) throws DBException {
        for (int i = savepoints.size() - 1; i > index; i--) {
            deleteDirectory(savepoints.remove(i).snapshot);
        }
    }

    private void clearTransactionState() throws DBException {
        if (transactionSnapshot != null) {
            deleteDirectory(transactionSnapshot);
            transactionSnapshot = null;
        }
        for (SavepointSnapshot savepoint : savepoints) {
            deleteDirectory(savepoint.snapshot);
        }
        savepoints.clear();
    }

    private void restoreSnapshot(Path snapshot) throws DBException {
        try {
            Path dbRoot = getDbRoot();
            deleteDirectoryContents(dbRoot);
            copyDirectoryContents(snapshot, dbRoot);
            dbManager.getBufferPool().ClearCache();
            dbManager.getDiskManager().reloadMetadata();
            dbManager.getMetaManager().reloadFromJson();
            dbManager.clearRuntimeIndexes();
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteDirectoryContents(Path root) throws IOException {
        if (!Files.exists(root)) {
            Files.createDirectories(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void deleteDirectory(Path root) throws DBException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }
}
