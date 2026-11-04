package com.example.online_reader.book.progress;

import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface ProgressRepository extends JpaRepository<ReadingProgress,Long> {
    Optional<ReadingProgress> findByBookIdAndOwnerId(Long book,Integer owner);
    @Modifying @Query("delete from ReadingProgress p where p.bookId=:book and p.ownerId=:owner")
    int deleteOwned(Long book,Integer owner);
    @Modifying
    @Query(value="insert into reading_progress(book_id,owner_id,chapter,position_offset,fingerprint,representation_version,revision,updated_at) values (:book,:owner,:chapter,:offset,:fingerprint,:version,1,CURRENT_TIMESTAMP) on conflict do nothing",nativeQuery=true)
    int create(Long book,Integer owner,String chapter,int offset,String fingerprint,int version);
    @Modifying
    @Query(value="update reading_progress set chapter=:chapter,position_offset=:offset,fingerprint=:fingerprint,representation_version=:version,revision=revision+1,updated_at=CURRENT_TIMESTAMP where book_id=:book and owner_id=:owner and revision=:revision",nativeQuery=true)
    int replace(Long book,Integer owner,String chapter,int offset,String fingerprint,int version,int revision);
}
