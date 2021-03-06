package de.qabel.qabelbox.persistence;

import de.qabel.core.config.Persistable;
import de.qabel.core.config.Persistence;
import de.qabel.core.exceptions.QblInvalidEncryptionKeyException;
import de.qabel.qabelbox.exceptions.QblPersistenceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AndroidPersistence extends Persistence<QblSQLiteParams> {

    private DatabaseWrapper databaseWrapper;

    private static final Logger LOGGER = LoggerFactory.getLogger(AndroidPersistence.class.getName());

    public AndroidPersistence(QblSQLiteParams params) throws QblInvalidEncryptionKeyException {
        this.databaseWrapper = createDatabaseWrapper(params);
        connect(params);
    }

    protected DatabaseWrapper createDatabaseWrapper(QblSQLiteParams params) {
        return new AndroidDatabaseWrapper(params);
    }

    @Override
    protected boolean connect(QblSQLiteParams qblSQLiteParams) {
        return databaseWrapper.connect();
    }

    @Override
    public boolean persistEntity(Persistable object) {
        if (object == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }

        String sql = "CREATE TABLE IF NOT EXISTS " +
                getTableNameForClass(object.getClass()) +
                "(ID TEXT PRIMARY KEY NOT NULL," +
                "BLOB BLOB NOT NULL)";

        try {
            databaseWrapper.execSQL(sql);
        } catch (QblPersistenceException e) {
            e.printStackTrace();
            LOGGER.error("Cannot create table!", e.getException());
        }

        return databaseWrapper.insert(object);
    }

    @Override
    public boolean updateEntity(Persistable object) {
        if (object == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }

        if (getEntity(object.getPersistenceID(), object.getClass()) == null) {
            LOGGER.info("Entity not stored!");
            return false;
        }

        return databaseWrapper.update(object);
    }

    @Override
    public boolean updateOrPersistEntity(Persistable object) {
        if (getEntity(object.getPersistenceID(), object.getClass()) == null) {
            return persistEntity(object);
        } else {
            return updateEntity(object);
        }
    }

    @Override
    public boolean removeEntity(String id, Class cls) {
        if (id == null || cls == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("ID cannot be empty!");
        }

        return databaseWrapper.delete(id, cls);
    }

    @Override
    public <U extends Persistable> U getEntity(String id, Class<? extends U> cls) {
        if (id == null || cls == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("ID cannot be empty!");
        }

        try {
            return databaseWrapper.getEntity(id, cls);
        } catch (QblPersistenceException e) {
            LOGGER.debug("Couldn't get entity! " + e.getException().getLocalizedMessage());
        }
        return null;
    }

    @Override
    public <U extends Persistable> List<U> getEntities(Class<? extends U> cls) {
        if (cls == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }

        try {
            return databaseWrapper.getEntities(cls);
        } catch (QblPersistenceException e) {
            LOGGER.info("Table does not exist!");
        }

        return new ArrayList<>();
    }

    @Override
    public boolean dropTable(Class cls) {
        if (cls == null) {
            throw new IllegalArgumentException("Arguments cannot be null!");
        }

        try {
            databaseWrapper.execSQL("DROP TABLE " + getTableNameForClass(cls));
        } catch (QblPersistenceException e) {
            LOGGER.info("Table does not exist!");
            return false;
        }
        return true;
    }

    private static String getTableNameForClass(Class cls) {
        return '\'' + cls.getCanonicalName() + '\'';
    }
}
