package com.example.online_reader.book.files;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;

/** Hold one dedicated PG session lock; root binding also rejects a wrong directory after restart. */
@Component
@DependsOn("entityManagerFactory")
public class BookStorageLease {
    private static final long LOCK_ID=0x446f73746f526561L;
    private final Connection connection;
    private final boolean postgres;
    private boolean valid=true;
    public BookStorageLease(DataSource dataSource,PrivateBookStorage storage)throws SQLException {
        connection=dataSource.getConnection();connection.setNetworkTimeout(Runnable::run,2000);
        postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        try {
            if(postgres) {
                try(var query=connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                    query.setQueryTimeout(2);query.setLong(1,LOCK_ID);
                    try(var rows=query.executeQuery()){rows.next();if(!rows.getBoolean(1))throw new SQLException("Book storage is already in use");}
                }
            }
            try(var insert=connection.prepareStatement("insert into book_storage(id,root_id) values (1,?) on conflict do nothing")) {
                insert.setQueryTimeout(2);insert.setObject(1,storage.identity());insert.executeUpdate();
            }
            try(var query=connection.createStatement()) {
                query.setQueryTimeout(2);
                try(var rows=query.executeQuery("select root_id from book_storage where id=1")) {
                    if(!rows.next() || !storage.identity().equals(rows.getObject(1,java.util.UUID.class)))throw new SQLException("Storage directory does not match database binding");
                }
            }
        }catch(SQLException | RuntimeException e){release();throw e;}
    }
    public synchronized void requireValid() {
        try{if(!valid || !connection.isValid(1))throw new SQLException();}
        catch(SQLException e){valid=false;throw BookFileException.busy();}
    }
    public boolean postgres(){return postgres;}
    @PreDestroy public synchronized void release() {
        valid=false;
        if(postgres)try(var query=connection.prepareStatement("select pg_advisory_unlock(?)")) {
            query.setQueryTimeout(2);query.setLong(1,LOCK_ID);query.execute();
        }catch(SQLException ignored){/* A closed PG session releases its advisory lock. */}
        try{connection.close();}catch(SQLException ignored){/* Already disconnected. */}
    }
}
