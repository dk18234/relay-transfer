package dev.dineshrama.relay;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="relay.storage",havingValue="local")
public class LocalStorage implements Storage {
    private final Path root;
    public LocalStorage(RelayProperties p) throws IOException { root=p.dataDirectory().resolve("local").toAbsolutePath().normalize(); Files.createDirectories(root); }
    public String kind(){return "local";}
    private Path resolve(String key) { Path p=root.resolve(key).normalize(); if(!p.startsWith(root))throw new IllegalArgumentException("Invalid storage key");return p; }
    public String putPart(UUID id,int number,Path staged) throws IOException {
        String key="parts/"+id+"/"+number+"-"+UUID.randomUUID();Path dst=resolve(key);Files.createDirectories(dst.getParent());
        Files.copy(staged,dst);Hashes.sync(dst);return key;
    }
    public InputStream openPart(String key)throws IOException{return Files.newInputStream(resolve(key));}
    public void assemble(UUID id,List<Transfer.Part> parts,long size)throws IOException{
        Path dst=resolve("objects/"+id);Files.createDirectories(dst.getParent());Path tmp=Files.createTempFile(dst.getParent(),"assembly-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(tmp)){for(Transfer.Part part:parts){try(InputStream in=openPart(part.key())){String sha=Hashes.copyExact(in,out,part.size(),null);if(!sha.equals(part.sha256()))throw new ApiException(422,"STORED_PART_CORRUPT","Stored part failed its SHA-256 check");}}}
            Hashes.sync(tmp);Files.move(tmp,dst,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    public InputStream openObject(UUID id)throws IOException{return Files.newInputStream(resolve("objects/"+id));}
    public void cleanupParts(UUID id)throws IOException{
        Path dir=resolve("parts/"+id);if(!Files.exists(dir))return;
        try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
