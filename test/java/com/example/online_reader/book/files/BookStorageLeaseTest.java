package com.example.online_reader.book.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class BookStorageLeaseTest {
    @TempDir Path directory;
    @Test void databaseBindingRejectsWrongDirectoryAfterRestart()throws Exception {
        var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:lease-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try(var connection=ds.getConnection();var statement=connection.createStatement()){statement.execute("create table book_storage(id integer primary key,root_id uuid not null)");}
        try(var storage=new PrivateBookStorage(directory.resolve("first").toString())) {
            var lease=new BookStorageLease(ds,storage);lease.requireValid();lease.release();
        }
        try(var wrong=new PrivateBookStorage(directory.resolve("wrong").toString())) {
            assertThatThrownBy(()->new BookStorageLease(ds,wrong)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("does not match");
        }
        try(var original=new PrivateBookStorage(directory.resolve("first").toString())) {
            var restarted=new BookStorageLease(ds,original);restarted.requireValid();restarted.release();
        }
    }
}
