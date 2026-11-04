package com.example.online_reader.book.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PrivateBookStorageTest {
    @TempDir Path directory;
    @Test void exclusiveRootAndTwoJobsAreEnforcedAndCloseReleasesCapacity()throws Exception {
        try(var storage=new PrivateBookStorage(directory.resolve("root").toString())) {
            assertThatThrownBy(()->new PrivateBookStorage(directory.resolve("root").toString())).isInstanceOf(java.io.IOException.class);
            var first=storage.admit();var second=storage.admit();
            assertThatThrownBy(storage::admit).isInstanceOf(BookFileException.class);
            first.close();try(var next=storage.admit()){assertThat(next).isNotNull();}
            second.close();
        }
        try(var reopened=new PrivateBookStorage(directory.resolve("root").toString())){assertThat(reopened).isNotNull();}
    }
    @Test void sourceIsNeverOverwrittenAndReadersPreventPrematureCleanup()throws Exception {
        try(var storage=new PrivateBookStorage(directory.resolve("root").toString());var job=storage.admit()) {
            UUID key=UUID.randomUUID();Files.writeString(job.source(),"hello");
            storage.beginWrite(key);storage.persist(key,job.source(),new ProcessingBudget());
            assertThat(storage.removeIfIdle(key)).isFalse();storage.endWrite(key);
            assertThatThrownBy(()->storage.persist(key,job.source(),new ProcessingBudget())).isInstanceOf(FileAlreadyExistsException.class);
            try(var input=storage.open(key,5)) {
                assertThat(storage.removeIfIdle(key)).isFalse();assertThat(new String(input.readAllBytes())).isEqualTo("hello");
            }
            assertThat(storage.removeIfIdle(key)).isTrue();assertThat(storage.removeIfIdle(key)).isTrue();
        }
    }
    @Test void startupCleansOwnedStagingButNeverFollowsSymlinks()throws Exception {
        Path root=directory.resolve("root");
        try(var storage=new PrivateBookStorage(root.toString())){ }
        var orphan=root.resolve("staging").resolve(UUID.randomUUID()+".request");Files.writeString(orphan,"partial");
        try(var storage=new PrivateBookStorage(root.toString())){assertThat(orphan).doesNotExist();}
        Path external=directory.resolve("external");Files.writeString(external,"untouched");
        Files.createSymbolicLink(root.resolve("staging").resolve(UUID.randomUUID()+".source"),external);
        assertThatThrownBy(()->new PrivateBookStorage(root.toString())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(external)).isEqualTo("untouched");
        Path link=directory.resolve("link");Files.createSymbolicLink(link,root);
        assertThatThrownBy(()->new PrivateBookStorage(link.toString())).isInstanceOf(java.io.IOException.class);
    }
    @Test void storageInsidePublicResourcesIsRejected() {
        assertThatThrownBy(()->new PrivateBookStorage("main/resources/static/private-books"))
                .isInstanceOf(java.io.IOException.class);
    }
    @Test void emptyAclIsNotEvidenceOfPrivacyAndForeignAllowIsRejected() {
        java.nio.file.attribute.UserPrincipal owner=()->"owner",foreign=()->"other";
        var own=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA,java.nio.file.attribute.AclEntryPermission.WRITE_DATA).build();
        var other=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(foreign)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build();
        assertThat(PrivateBookStorage.privateAcl(owner,List.of())).isFalse();
        assertThat(PrivateBookStorage.privateAcl(owner,List.of(own))).isTrue();
        assertThat(PrivateBookStorage.privateAcl(owner,List.of(own,other))).isFalse();
    }

}
