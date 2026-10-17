package io.github.qianlixunbai.workspace.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Synthetic bytes/rows only; does not impersonate Windows private-root admission. */
final class KnowledgePostgresqlFixture {
    static final String TIME="2026-10-17T10:00:00.123456789Z";
    static List<String> create(Connection db,Path root)throws Exception{
        try(var s=db.createStatement()){s.execute("PRAGMA foreign_keys=ON");for(String ddl:KnowledgeStore.SCHEMA_DDL)s.execute(ddl);s.execute("PRAGMA user_version=1");}
        List<String> ids=new ArrayList<>();
        String[][] sources={{"budget.txt","other filler"},{"heading.md","# budget\r\nother filler"},{"body.txt","budget filler"},
            {"unicode.md","\uFEFF# 预算 Café\r\n预算 budget ＢＵＤＧＥＴ cafe\u0301 😀 ' \" % _ punctuation-boundary\r\n"},
            {"tie1.txt","tie exact"},{"tie2.txt","tie exact "},{"split.md","# One\nalpha\n# Two\nbeta"}};
        db.setAutoCommit(false);
        try{
            for(String[] entry:sources){String id=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();ids.add(id);
                Path directory=root.resolve("sources").resolve(id);Files.createDirectories(directory);Path file=directory.resolve("1.source");
                byte[] bytes=entry[1].getBytes(StandardCharsets.UTF_8);Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
                String type=KnowledgeParser.type(entry[0]);var parsed=KnowledgeParser.parse(file,type,()->false);
                try(var s=db.prepareStatement("INSERT INTO documents VALUES(?,?,'ACTIVE',2,1,?,?,?)")){
                    s.setString(1,id);s.setString(2,entry[0]);s.setString(3,TIME);s.setString(4,TIME);s.setString(5,job);s.executeUpdate();}
                try(var s=db.prepareStatement("INSERT INTO revisions VALUES(?,1,?,?,?,?,?,?,?,?,?,?,?,?)")){
                    Object[] values={id,KnowledgeParser.sourceDigest(file),entry[0],type,bytes.length,TIME,KnowledgeParser.PARSER_VERSION,KnowledgeParser.NORMALIZATION_VERSION,parsed.digest(),parsed.text(),parsed.locatorJson(),parsed.lineCount(),parsed.text().getBytes(StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(StandardCharsets.UTF_8).length};
                    for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}
                try(var s=db.prepareStatement("INSERT INTO jobs VALUES(?,?,'READY',NULL,1,?,?,?,1,?)")){
                    s.setString(1,job);s.setString(2,id);s.setString(3,entry[0]);s.setString(4,type);s.setLong(5,bytes.length);s.setString(6,TIME);s.executeUpdate();}
            }
            db.commit();return ids;
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
}
