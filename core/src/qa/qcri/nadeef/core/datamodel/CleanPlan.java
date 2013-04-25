/*
 * Copyright (C) Qatar Computing Research Institute, 2013.
 * All rights reserved.
 */

package qa.qcri.nadeef.core.datamodel;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import org.jooq.SQLDialect;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import qa.qcri.nadeef.core.exception.InvalidCleanPlanException;
import qa.qcri.nadeef.core.exception.InvalidRuleException;
import qa.qcri.nadeef.core.util.Bootstrap;
import qa.qcri.nadeef.core.util.DBConnectionFactory;
import qa.qcri.nadeef.tools.CSVDumper;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Nadeef cleaning plan.
 */
public class CleanPlan {
    private DBConfig source;
    private DBConfig target;
    private List<Rule> rules;

    //<editor-fold desc="Constructor">
    /**
     * Constructor.
     */
    public CleanPlan(
        DBConfig sourceConfig,
        DBConfig targetConfig,
        List<Rule> rules
    ) {
        this.source = sourceConfig;
        this.target = targetConfig;
        this.rules = rules;
    }

    //</editor-fold>

    /**
     * Creates a <code>CleanPlan</code> from JSON string.
     * @param reader JSON string reader.
     * @return <code>CleanPlan</code> object.
     */
    public static CleanPlan createCleanPlanFromJSON(Reader reader)
        throws
            InvalidRuleException,
            InvalidCleanPlanException {
        Preconditions.checkNotNull(reader);

        SQLDialect sqlDialect;
        String sourceUrl = null;
        String sourceTableUserName = null;
        String sourceTableUserPassword = null;
        String csvTableName = null;
        boolean isCSV = false;

        JSONObject jsonObject = (JSONObject)JSONValue.parse(reader);

        try {
            // ----------------------------------------
            // parsing the source config
            // ----------------------------------------
            JSONObject src = (JSONObject)jsonObject.get("source");
            String type = (String)src.get("type");
            if (type.equalsIgnoreCase("csv")) {
                isCSV = true;
                String fileName = (String)src.get("file");
                // TODO: find a better way to parse the file name.
                fileName = fileName.replace("\\", "\\\\");
                fileName = fileName.replace("\t", "\\t");
                fileName = fileName.replace("\n", "\\n");
                File file = new File(fileName);
                // source is a CSV file, dump it first.
                Connection conn = DBConnectionFactory.createNadeefConnection();
                // TODO: find a way to clean the table after exiting.
                csvTableName = CSVDumper.dump(conn, file);
                sqlDialect = SQLDialect.POSTGRES;
                conn.close();
                sourceUrl = NadeefConfiguration.getUrl();
                sourceTableUserName = NadeefConfiguration.getUserName();
                sourceTableUserPassword = NadeefConfiguration.getPassword();
            } else {
                // TODO: support different type of DB.
                sqlDialect = SQLDialect.POSTGRES;

                sourceUrl = (String)src.get("url");
                sourceTableUserName = (String)src.get("username");
                sourceTableUserPassword = (String)src.get("password");
            }
            DBConfig source =
                new DBConfig(
                    sourceTableUserName,
                    sourceTableUserPassword,
                    sourceUrl,
                    sqlDialect
                );

            // ----------------------------------------
            // parsing the target config
            // ----------------------------------------
            // TODO: fill the target parsing

            // ----------------------------------------
            // parsing the rules
            // ----------------------------------------
            // TODO: adds verification on the fd rule attributes arguments.
            // TODO: adds verification on the table name check.
            // TODO: adds verification on the hints.
            // TODO: use token.matches("^\\s*(\\w+\\.?){0,3}\\w\\s*$") to match the pattern.
            JSONArray ruleArray = (JSONArray)jsonObject.get("rule");
            ArrayList<Rule> rules = new ArrayList();
            for (int i = 0; i < ruleArray.size(); i ++) {
                JSONObject ruleObj = (JSONObject)ruleArray.get(i);
                String name = (String)ruleObj.get("name");
                if (Strings.isNullOrEmpty(name)) {
                    name = "Rule " + i;
                }

                List<String> tableNames;
                if (isCSV) {
                    tableNames = Arrays.asList(csvTableName);
                } else {
                    tableNames = (List<String>)ruleObj.get("table");
                }

                if (tableNames.size() > 2 || tableNames.size() < 1) {
                    throw new IllegalArgumentException(
                        "Invalid Rule property, rule needs to have one or two tables."
                    );
                }

                type = (String)ruleObj.get("type");
                Rule rule = null;
                JSONArray value;
                switch (type) {
                    case "fd":
                        value = (JSONArray)ruleObj.get("value");
                        Preconditions.checkArgument(
                            value != null && value.size() == 1,
                            "Type value cannot be null or empty."
                        );
                        rule =
                            new FDRule(
                                name,
                                tableNames,
                                new StringReader((String)value.get(0))
                            );
                        rules.add(rule);
                        break;
                    case "udf":
                        rule = parseUdf(ruleObj, name, tableNames);
                        rules.add(rule);
                        break;
                    case "cfd":
                        value = (JSONArray)ruleObj.get("value");
                        Preconditions.checkArgument(
                            value != null && value.size() == 2,
                            "Type value cannot be null or empty."
                        );
                        String columnLine = (String)value.get(0);
                        for (int j = 1; j < value.size(); j ++) {
                            StringReader cfdLine =
                                new StringReader(columnLine + "\n" + value.get(j));
                            rule = new CFDRule(name, tableNames, cfdLine);
                            rules.add(rule);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown rule type.");
                }
            }
            return new CleanPlan(source, null, rules);
        } catch (Exception ex) {
            throw new InvalidCleanPlanException(ex.getMessage());
        }
    }

    //<editor-fold desc="Property Getters">

    /**
     * Gets the <code>DBConfig</code> for the clean source.
     * @return <code>DBConfig</code>.
     */
    public DBConfig getSourceDBConfig() {
        return source;
    }

    /**
     * Gets the rules in the <code>CleanPlan</code>.
     * @return a list of <code>Rule</code>.
     */
    public List<Rule> getRules() {
        return rules;
    }

    //</editor-fold>
    private static Rule parseUdf(
        JSONObject ruleObj,
        String ruleId,
        List<String> tableNames
    ) throws InvalidRuleException {
        try {
            JSONArray value = (JSONArray)ruleObj.get("value");
            Preconditions.checkArgument(value != null && value.size() == 1);

            String className = (String)value.get(0);
            Class udfClass = Bootstrap.loadClass(className);
            if (!Rule.class.isAssignableFrom(udfClass)) {
                throw
                    new IllegalArgumentException(
                        "The specified class is not a Rule class."
                    );
            }

            Rule rule = (Rule)udfClass.newInstance();
            // call internal initialization on the rule.
            rule.initialize(ruleId, tableNames);
            return rule;
        } catch (Exception ex) {
            throw new InvalidRuleException(ex.getMessage());
        }
    }
}
