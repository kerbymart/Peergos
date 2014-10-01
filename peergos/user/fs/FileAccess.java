package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class FileAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent = new TreeMap();
    private final SymmetricLink parent2meta;

    // public data
    private List<ByteArrayWrapper> fragments;

    public FileAccess(Set<UserPublicKey> sharingR, SymmetricKey metaKey, SymmetricKey parentKey, FileProperties metadata, List<ByteArrayWrapper> fragments, byte[] iv)
    {
        super(TYPE.FILE, metaKey.encrypt(metadata.serialize(), iv));
        this.fragments = fragments;
        this.parent2meta = new SymmetricLink(parentKey, metaKey, iv);
        if (sharingR != null) {
            for (UserPublicKey key: sharingR)
                sharingR2parent.put(key, new AsymmetricLink(key, parentKey));
        }
    }

    public FileAccess(SymmetricKey parentKey, FileProperties metadata, List<ByteArrayWrapper> fragments)
    {
        this(null, SymmetricKey.random(), parentKey, metadata, fragments, metadata.getIV());
    }

    public FileAccess(byte[] m, byte[] p2m, Map<UserPublicKey, AsymmetricLink> sharingR)
    {
        super(TYPE.FILE, m);
        parent2meta = new SymmetricLink(p2m);
        sharingR2parent.putAll(sharingR);
    }

    public void setFragments(List<ByteArrayWrapper> fragments) {
        this.fragments = fragments;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        dout.writeInt(sharingR2parent.size());
        for (UserPublicKey key: sharingR2parent.keySet()) {
            Serialize.serialize(key.getPublicKey(), dout);
            Serialize.serialize(sharingR2parent.get(key).serialize(), dout);
        }
    }

    public static FileAccess deserialize(DataInput din, byte[] metadata) throws IOException
    {
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int count = din.readInt();
        Map<UserPublicKey, AsymmetricLink> sharingR = new HashMap();
        for (int i=0; i < count; i++) {
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        FileAccess res = new FileAccess(metadata, p2m, sharingR);
        return res;
    }

    public List<ByteArrayWrapper> getFragmentHashes() {
        return Collections.unmodifiableList(fragments);
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey)
    {
        return sharingR2parent.get(sharingKey.getKey()).target(sharingKey);
    }

    public ChunkProperties getProps(SymmetricKey baseKey, byte[] iv) {
        return FileProperties.deserialize(baseKey.decrypt(encryptedMetadata, iv));
    }

    public FileProperties getProps(SymmetricKey parentKey)
    {
        return (FileProperties) getProps(parent2meta.target(parentKey), parent2meta.initializationVector());
    }
}
