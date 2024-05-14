/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import org.lealone.common.exceptions.ConfigException;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.CamelCaseHelper;
import org.lealone.common.util.CaseInsensitiveMap;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.DbSetting;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.constraint.ConstraintReferential;
import org.lealone.db.index.IndexColumn;
import org.lealone.db.lock.DbObjectLock;
import org.lealone.db.schema.Schema;
import org.lealone.db.schema.Sequence;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Column;
import org.lealone.db.table.Column.ListColumn;
import org.lealone.db.table.Column.MapColumn;
import org.lealone.db.table.Column.SetColumn;
import org.lealone.db.table.CreateTableData;
import org.lealone.db.table.Table;
import org.lealone.db.table.TableSetting;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.dml.Insert;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.optimizer.TableFilter;
import org.lealone.sql.query.Query;
import org.lealone.storage.StorageSetting;

/**
 * This class represents the statement
 * CREATE TABLE
 * 
 * @author H2 Group
 * @author zhh
 */
public class CreateTable extends SchemaStatement {

    protected final CreateTableData data = new CreateTableData();
    protected IndexColumn[] pkColumns;
    protected boolean ifNotExists;

    private final ArrayList<DefinitionStatement> constraintCommands = new ArrayList<>();
    private boolean onCommitDrop;
    private boolean onCommitTruncate;
    private Query asQuery;
    private String comment;
    private String packageName;
    private boolean genCode;
    private String codePath;

    public CreateTable(ServerSession session, Schema schema) {
        super(session, schema);
        data.persistIndexes = true;
        data.persistData = true;
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_TABLE;
    }

    public void setQuery(Query query) {
        this.asQuery = query;
    }

    public void setTemporary(boolean temporary) {
        data.temporary = temporary;
    }

    public void setTableName(String tableName) {
        data.tableName = tableName;
    }

    /**
     * Add a column to this table.
     *
     * @param column the column to add
     */
    public void addColumn(Column column) {
        data.columns.add(column);
    }

    /**
     * Add a constraint statement to this statement.
     * The primary key definition is one possible constraint statement.
     *
     * @param command the statement to add
     */
    public void addConstraintCommand(DefinitionStatement command) {
        if (command instanceof CreateIndex) {
            constraintCommands.add(command);
        } else {
            AlterTableAddConstraint con = (AlterTableAddConstraint) command;
            boolean alreadySet;
            if (con.getType() == SQLStatement.ALTER_TABLE_ADD_CONSTRAINT_PRIMARY_KEY) {
                alreadySet = setPrimaryKeyColumns(con.getIndexColumns());
            } else {
                alreadySet = false;
            }
            if (!alreadySet) {
                constraintCommands.add(command);
            }
        }
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    private void validateParameters() {
        CaseInsensitiveMap<String> parameters = new CaseInsensitiveMap<>();
        if (data.storageEngineParams != null)
            parameters.putAll(data.storageEngineParams);
        if (parameters.isEmpty())
            return;

        HashSet<String> recognizedSettingOptions = new HashSet<>(
                StorageSetting.values().length + TableSetting.values().length);
        recognizedSettingOptions.addAll(DbSetting.getRecognizedStorageSetting());
        for (StorageSetting s : StorageSetting.values())
            recognizedSettingOptions.add(s.name());
        for (TableSetting s : TableSetting.values())
            recognizedSettingOptions.add(s.name());

        parameters.removeAll(recognizedSettingOptions);
        if (!parameters.isEmpty()) {
            throw new ConfigException(String.format("Unrecognized parameters: %s for table %s, " //
                    + "recognized setting options: %s", //
                    parameters.keySet(), data.tableName, recognizedSettingOptions));
        }
    }

    @Override
    public int update() {
        validateParameters();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.TABLE_OR_VIEW, session);
        if (lock == null)
            return -1;

        Database db = session.getDatabase();
        if (!db.isPersistent()) {
            data.persistIndexes = false;
        }
        if (schema.findTableOrView(session, data.tableName) != null) {
            if (ifNotExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, data.tableName);
        }
        if (asQuery != null) {
            asQuery.prepare();
            if (data.columns.isEmpty()) {
                generateColumnsFromQuery();
            } else if (data.columns.size() != asQuery.getColumnCount()) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
        if (pkColumns != null) {
            for (Column c : data.columns) {
                for (IndexColumn idxCol : pkColumns) {
                    if (c.getName().equals(idxCol.columnName)) {
                        c.setNullable(false);
                    }
                }
            }
        }
        data.id = getObjectId();
        data.create = !session.getDatabase().isStarting();
        data.session = session;
        boolean isSessionTemporary = data.temporary && !data.globalTemporary;
        Table table = schema.createTable(data);
        ArrayList<Sequence> sequences = new ArrayList<>();
        for (Column c : data.columns) {
            if (c.isAutoIncrement()) {
                int objId = getObjectId();
                c.convertAutoIncrementToSequence(session, schema, objId, data.temporary, lock);
            }
            Sequence seq = c.getSequence();
            if (seq != null) {
                sequences.add(seq);
            }
        }
        table.setComment(comment);
        table.setPackageName(packageName);
        table.setCodePath(codePath);
        if (isSessionTemporary) {
            if (onCommitDrop) {
                table.setOnCommitDrop(true);
            }
            if (onCommitTruncate) {
                table.setOnCommitTruncate(true);
            }
            session.addLocalTempTable(table);
        } else {
            schema.add(session, table, lock);
        }
        try {
            TableFilter tf = new TableFilter(session, table, null, false, null);
            for (Column c : data.columns) {
                c.prepareExpression(session, tf);
            }
            for (Sequence sequence : sequences) {
                table.addSequence(sequence);
            }
            for (DefinitionStatement command : constraintCommands) {
                command.update();
            }
            if (asQuery != null) {
                Insert insert = new Insert(session);
                insert.setQuery(asQuery);
                insert.setTable(table);
                insert.prepare();
                insert.update();
            }
        } catch (DbException e) {
            db.checkPowerOff();
            schema.remove(session, table, lock);
            throw e;
        }

        // 数据库在启动阶段执行建表语句时不用再生成代码
        if (genCode && !session.getDatabase().isStarting())
            genCode(session, table, table, 1);
        return 0;
    }

    private void generateColumnsFromQuery() {
        int columnCount = asQuery.getColumnCount();
        ArrayList<Expression> expressions = asQuery.getExpressions();
        for (int i = 0; i < columnCount; i++) {
            Expression expr = expressions.get(i);
            int type = expr.getType();
            String name = expr.getAlias();
            long precision = expr.getPrecision();
            int displaySize = expr.getDisplaySize();
            DataType dt = DataType.getDataType(type);
            if (precision > 0 && //
                    (dt.defaultPrecision == 0 //
                            || (dt.defaultPrecision > precision
                                    && dt.defaultPrecision < Byte.MAX_VALUE))) {
                // dont' set precision to MAX_VALUE if this is the default
                precision = dt.defaultPrecision;
            }
            int scale = expr.getScale();
            if (scale > 0 && (dt.defaultScale == 0
                    || (dt.defaultScale > scale && dt.defaultScale < precision))) {
                scale = dt.defaultScale;
            }
            if (scale > precision) {
                precision = scale;
            }
            Column col = new Column(name, type, precision, scale, displaySize);
            addColumn(col);
        }
    }

    /**
     * Sets the primary key columns, but also check if a primary key
     * with different columns is already defined.
     *
     * @param columns the primary key columns
     * @return true if the same primary key columns where already set
     */
    private boolean setPrimaryKeyColumns(IndexColumn[] columns) {
        if (pkColumns != null) {
            int len = columns.length;
            if (len != pkColumns.length) {
                throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
            }
            for (int i = 0; i < len; i++) {
                if (!columns[i].columnName.equals(pkColumns[i].columnName)) {
                    throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
                }
            }
            return true;
        }
        this.pkColumns = columns;
        return false;
    }

    public void setPersistIndexes(boolean persistIndexes) {
        data.persistIndexes = persistIndexes;
    }

    public void setPersistData(boolean persistData) {
        data.persistData = persistData;
        if (!persistData) {
            data.persistIndexes = false;
        }
    }

    public void setGlobalTemporary(boolean globalTemporary) {
        data.globalTemporary = globalTemporary;
    }

    public void setHidden(boolean isHidden) {
        data.isHidden = isHidden;
    }

    /**
     * This temporary table is dropped on commit.
     */
    public void setOnCommitDrop() {
        this.onCommitDrop = true;
    }

    /**
     * This temporary table is truncated on commit.
     */
    public void setOnCommitTruncate() {
        this.onCommitTruncate = true;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setStorageEngineName(String storageEngineName) {
        data.storageEngineName = storageEngineName;
    }

    public void setStorageEngineParams(CaseInsensitiveMap<String> storageEngineParams) {
        data.storageEngineParams = storageEngineParams;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setGenCode(boolean genCode) {
        this.genCode = genCode;
    }

    public void setCodePath(String codePath) {
        this.codePath = codePath;
    }

    public boolean isGenCode() {
        return genCode;
    }

    public String getCodePath() {
        return codePath;
    }

    private static void genCode(ServerSession session, Table table, Table owner, int level) {
        String packageName = table.getPackageName();
        String tableName = table.getName();
        String className = CreateService.toClassName(tableName);
        Database db = session.getDatabase();
        Schema schema = table.getSchema();
        boolean databaseToUpper = db.getSettings().databaseToUpper;

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            if (refTable != table && level <= 1) { // 避免递归
                genCode(session, refTable, owner, ++level);
            }
        }

        // 收集需要导入的类
        TreeSet<String> importSet = new TreeSet<>();
        importSet.add("org.lealone.plugins.orm.Model");
        importSet.add("org.lealone.plugins.orm.ModelTable");
        importSet.add("org.lealone.plugins.orm.ModelProperty");
        importSet.add("org.lealone.plugins.orm.format.JsonFormat");

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            owner = ref.getTable();
            if (refTable == table) {
                String pn = owner.getPackageName();
                if (!packageName.equals(pn)) {
                    importSet.add(pn + "." + CreateService.toClassName(owner.getName()));
                }
                importSet.add(List.class.getName());
            } else {
                String pn = refTable.getPackageName();
                if (!packageName.equals(pn)) {
                    importSet.add(pn + "." + CreateService.toClassName(refTable.getName()));
                }
            }
        }

        StringBuilder fields = new StringBuilder();
        StringBuilder fieldNames = new StringBuilder();
        StringBuilder initFields = new StringBuilder();

        for (Column c : table.getColumns()) {
            int type = c.getType();
            String modelPropertyClassName = getModelPropertyClassName(type, importSet);
            String columnName = CamelCaseHelper.toCamelFromUnderscore(c.getName());

            fields.append("    public final ").append(modelPropertyClassName).append('<')
                    .append(className);
            if (c instanceof ListColumn) {
                fields.append(", ");
                ListColumn lc = (ListColumn) c;
                fields.append(getTypeName(lc.element, importSet));
            } else if (c instanceof SetColumn) {
                fields.append(", ");
                SetColumn sc = (SetColumn) c;
                fields.append(getTypeName(sc.element, importSet));
            } else if (c instanceof MapColumn) {
                fields.append(", ");
                MapColumn mc = (MapColumn) c;
                fields.append(getTypeName(mc.key, importSet));
                fields.append(", ");
                fields.append(getTypeName(mc.value, importSet));
            }
            fields.append("> ").append(columnName).append(";\r\n");

            // 例如: id = new PLong<>("id", this);
            initFields.append("        ").append(columnName).append(" = new ")
                    .append(modelPropertyClassName).append("<>(\"")
                    .append(databaseToUpper ? c.getName().toUpperCase() : c.getName())
                    .append("\", this");
            if (c instanceof MapColumn) {
                MapColumn mc = (MapColumn) c;
                initFields.append(", ").append(getTypeName(mc.key, importSet)).append(".class");
            }
            initFields.append(");\r\n");
            if (fieldNames.length() > 0) {
                fieldNames.append(", ");
            }
            fieldNames.append(columnName);
        }

        /////////////////////////// 以下是表关联的相关代码 ///////////////////////////

        // associate method(set, get, add) buff
        StringBuilder amBuff = new StringBuilder();
        StringBuilder adderBuff = new StringBuilder();
        StringBuilder adderInitBuff = new StringBuilder();
        StringBuilder setterBuff = new StringBuilder();
        StringBuilder setterInitBuff = new StringBuilder();

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            owner = ref.getTable();
            String refTableClassName = CreateService.toClassName(refTable.getName());
            if (refTable == table) {
                String ownerClassName = CreateService.toClassName(owner.getName());
                // add方法，增加单个model实例
                amBuff.append("    public ").append(className).append(" add").append(ownerClassName)
                        .append("(").append(ownerClassName).append(" m) {\r\n");
                amBuff.append("        m.set").append(refTableClassName).append("(this);\r\n");
                amBuff.append("        super.addModel(m);\r\n");
                amBuff.append("        return this;\r\n");
                amBuff.append("    }\r\n");
                amBuff.append("\r\n");

                // add方法，增加多个model实例
                amBuff.append("    public ").append(className).append(" add").append(ownerClassName)
                        .append("(").append(ownerClassName).append("... mArray) {\r\n");
                amBuff.append("        for (").append(ownerClassName).append(" m : mArray)\r\n");
                amBuff.append("            add").append(ownerClassName).append("(m);\r\n");
                amBuff.append("        return this;\r\n");
                amBuff.append("    }\r\n");
                amBuff.append("\r\n");

                // get list方法
                amBuff.append("    public List<").append(ownerClassName).append("> get")
                        .append(ownerClassName).append("List() {\r\n");
                amBuff.append("        return super.getModelList(").append(ownerClassName)
                        .append(".class);\r\n");
                amBuff.append("    }\r\n");
                amBuff.append("\r\n");

                // Adder类
                IndexColumn[] refColumns = ref.getRefColumns();
                IndexColumn[] columns = ref.getColumns();
                adderBuff.append("    protected class ").append(ownerClassName)
                        .append("Adder implements AssociateAdder<").append(ownerClassName)
                        .append("> {\r\n");
                adderBuff.append("        @Override\r\n");
                adderBuff.append("        public ").append(ownerClassName).append(" getDao() {\r\n");
                adderBuff.append("            return ").append(ownerClassName).append(".dao;\r\n");
                adderBuff.append("        }\r\n");
                adderBuff.append("\r\n");
                adderBuff.append("        @Override\r\n");
                adderBuff.append("        public void add(").append(ownerClassName).append(" m) {\r\n");
                adderBuff.append("            if (");
                for (int i = 0; i < columns.length; i++) {
                    if (i != 0) {
                        adderBuff.append(" && ");
                    }
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    adderBuff.append("areEqual(").append(refColumnName).append(", m.").append(columnName)
                            .append(")");
                }
                adderBuff.append(") {\r\n");
                adderBuff.append("                add").append(ownerClassName).append("(m);\r\n");
                adderBuff.append("            }\r\n");
                adderBuff.append("        }\r\n");
                adderBuff.append("    }\r\n");
                adderBuff.append("\r\n");

                // new Adder()
                if (adderInitBuff.length() > 0)
                    adderInitBuff.append(", ");
                adderInitBuff.append("new ").append(ownerClassName).append("Adder()");
            } else {
                String refTableVar = CamelCaseHelper.toCamelFromUnderscore(refTable.getName());

                // 引用表字段
                fields.append("    private ").append(refTableClassName).append(" ").append(refTableVar)
                        .append(";\r\n");

                // get方法
                amBuff.append("    public ").append(refTableClassName).append(" get")
                        .append(refTableClassName).append("() {\r\n");
                amBuff.append("        return ").append(refTableVar).append(";\r\n");
                amBuff.append("    }\r\n");
                amBuff.append("\r\n");

                // set方法
                amBuff.append("    public ").append(className).append(" set").append(refTableClassName)
                        .append("(").append(refTableClassName).append(" ").append(refTableVar)
                        .append(") {\r\n");
                amBuff.append("        this.").append(refTableVar).append(" = ").append(refTableVar)
                        .append(";\r\n");

                IndexColumn[] refColumns = ref.getRefColumns();
                IndexColumn[] columns = ref.getColumns();
                for (int i = 0; i < columns.length; i++) {
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    amBuff.append("        this.").append(columnName).append(".set(").append(refTableVar)
                            .append(".").append(refColumnName).append(".get());\r\n");
                }
                amBuff.append("        return this;\r\n");
                amBuff.append("    }\r\n");
                amBuff.append("\r\n");

                // Setter类
                setterBuff.append("    protected class ").append(refTableClassName)
                        .append("Setter implements AssociateSetter<").append(refTableClassName)
                        .append("> {\r\n");
                setterBuff.append("        @Override\r\n");
                setterBuff.append("        public ").append(refTableClassName).append(" getDao() {\r\n");
                setterBuff.append("            return ").append(refTableClassName).append(".dao;\r\n");
                setterBuff.append("        }\r\n");
                setterBuff.append("\r\n");
                setterBuff.append("        @Override\r\n");
                setterBuff.append("        public boolean set(").append(refTableClassName)
                        .append(" m) {\r\n");
                setterBuff.append("            if (");
                for (int i = 0; i < columns.length; i++) {
                    if (i != 0) {
                        setterBuff.append(" && ");
                    }
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    setterBuff.append("areEqual(").append(columnName).append(", m.")
                            .append(refColumnName).append(")");
                }
                setterBuff.append(") {\r\n");
                setterBuff.append("                set").append(refTableClassName).append("(m);\r\n");
                setterBuff.append("                return true;\r\n");
                setterBuff.append("            }\r\n");
                setterBuff.append("            return false;\r\n");
                setterBuff.append("        }\r\n");
                setterBuff.append("    }\r\n");
                setterBuff.append("\r\n");

                // new Setter()
                if (setterInitBuff.length() > 0)
                    setterInitBuff.append(", ");
                setterInitBuff.append("new ").append(refTableClassName).append("Setter()");
            }
        }

        /////////////////////////// 以下是生成model类的相关代码 ///////////////////////////

        StringBuilder buff = new StringBuilder();
        buff.append("package ").append(packageName).append(";\r\n\r\n");
        for (String p : importSet) {
            buff.append("import ").append(p).append(";\r\n");
        }
        buff.append("\r\n");
        buff.append("/**\r\n");
        buff.append(" * Model for table '").append(tableName).append("'.\r\n");
        buff.append(" *\r\n");
        buff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\r\n");
        buff.append(" */\r\n");
        // 例如: public class Customer extends Model<Customer> {
        buff.append("public class ").append(className).append(" extends Model<").append(className)
                .append("> {\r\n");
        buff.append("\r\n");

        // static create 方法
        // buff.append(" public static ").append(className).append(" create(String url) {\r\n");
        // buff.append(" ModelTable t = new ModelTable(url, ").append(tableFullName).append(");\r\n");
        // buff.append(" return new ").append(className).append("(t, REGULAR_MODEL);\r\n");
        // buff.append(" }\r\n");
        // buff.append("\r\n");

        // static dao字段
        String daoName = table.getParameter(TableSetting.DAO_NAME.name());
        if (daoName == null)
            daoName = "dao";
        buff.append("    public static final ").append(className).append(" ").append(daoName)
                .append(" = new ").append(className).append("(null, ROOT_DAO);\r\n");
        buff.append("\r\n");

        // 字段
        buff.append(fields);
        buff.append("\r\n");

        // 默认构造函数
        buff.append("    public ").append(className).append("() {\r\n");
        buff.append("        this(null, REGULAR_MODEL);\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");

        String tableFullName = "\"" + db.getName() + "\", \"" + schema.getName() + "\", \"" + tableName
                + "\"";
        if (databaseToUpper) {
            tableFullName = tableFullName.toUpperCase();
        }
        String jsonFormatName = table.getParameter(TableSetting.JSON_FORMAT.name());
        // 内部构造函数
        buff.append("    private ").append(className).append("(ModelTable t, short modelType) {\r\n");
        buff.append("        super(t == null ? new ModelTable(").append(tableFullName)
                .append(") : t, modelType);\r\n");
        buff.append(initFields);
        if (jsonFormatName != null)
            buff.append("        super.setJsonFormat(\"").append(jsonFormatName).append("\");\r\n");
        buff.append("        super.setModelProperties(new ModelProperty[] { ").append(fieldNames)
                .append(" });\r\n");
        if (setterInitBuff.length() > 0) {
            buff.append("        super.initSetters(").append(setterInitBuff).append(");\r\n");
        }
        if (adderInitBuff.length() > 0) {
            buff.append("        super.initAdders(").append(adderInitBuff).append(");\r\n");
        }
        buff.append("    }\r\n");
        buff.append("\r\n");

        // newInstance方法
        buff.append("    @Override\r\n");
        buff.append("    protected ").append(className)
                .append(" newInstance(ModelTable t, short modelType) {\r\n");
        buff.append("        return new ").append(className).append("(t, modelType);\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");

        // associate method
        buff.append(amBuff);

        // Setter类
        if (setterBuff.length() > 0) {
            buff.append(setterBuff);
        }

        // Adder类
        if (adderBuff.length() > 0) {
            buff.append(adderBuff);
        }

        // static decode方法
        buff.append("    public static ").append(className).append(" decode(String str) {\r\n");
        buff.append("        return decode(str, null);\r\n");
        buff.append("    }\r\n\r\n");
        buff.append("    public static ").append(className)
                .append(" decode(String str, JsonFormat format) {\r\n");
        buff.append("        return new ").append(className).append("().decode0(str, format);\r\n");
        buff.append("    }\r\n");
        buff.append("}\r\n");

        CreateService.writeFile(table.getCodePath(), packageName, className, buff);
    }

    private static String getModelPropertyClassName(int type, TreeSet<String> importSet) {
        String name;
        switch (type) {
        case Value.BYTES:
            name = "Bytes";
            break;
        case Value.UUID:
            name = "Uuid";
            break;
        case Value.NULL:
            throw DbException.getInternalError("type = null");
        default:
            name = DataType.getTypeClassName(type);
            int pos = name.lastIndexOf('.');
            name = name.substring(pos + 1);
        }
        name = "P" + name;
        importSet.add("org.lealone.plugins.orm.property." + name);
        return name;
    }

    private static String getTypeName(Column c, TreeSet<String> importSet) {
        String name = CreateService.getTypeName(c, importSet);
        // if (name.equals("Object"))
        // name = "?";
        return name;
    }
}
