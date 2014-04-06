package defiance.corenode;

import defiance.crypto.*;
import defiance.util.ByteArrayWrapper;

import java.security.PublicKey;

import java.util.*;
import java.io.*;
import java.net.*;


public abstract class AbstractCoreNode
{
    /**
     * Maintains meta-data about fragments stored in the DHT,
     * the relationship between users and with whom user fragments
     * are shared.      
     */ 
    class UserData
    {
        private final Set<UserPublicKey> friends;
        private final Map<ByteArrayWrapper, ByteArrayWrapper> fragments;

        UserData()
        {
            this.friends = new HashSet<UserPublicKey>();
            this.fragments = new HashMap<ByteArrayWrapper, ByteArrayWrapper>();
        }
    }   

    protected final Map<UserPublicKey, UserData> userMap;
    protected final Map<String, UserPublicKey> userNameToPublicKeyMap;
    protected final Map<UserPublicKey, String> userPublicKeyToNameMap;

    public AbstractCoreNode() throws Exception
    {
        this.userMap = new HashMap<UserPublicKey, UserData>();
        this.userNameToPublicKeyMap = new HashMap<String, UserPublicKey>();
        this.userPublicKeyToNameMap = new HashMap<UserPublicKey, String>();
    } 

    /*
     * @param userKey X509 encoded public key
     * @param signedHash the SHA hash of bytes in the username, signed with the user private key 
     * @param username the username that is being claimed
     */
    public boolean addUsername(byte[] userKey, byte[] signedHash, String username)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! key.isValidSignature(signedHash, username.getBytes()))
            return false;

        synchronized(this)
        {
            if (userNameToPublicKeyMap.containsKey(username))
                return false;

            userNameToPublicKeyMap.put(username, key); 
            userPublicKeyToNameMap.put(key, username); 
            userMap.put(key, new UserData());
            return true;
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a friend
     * @param signedHash the SHA hash of userBencodedKey, signed with the user private key 
     * @param userBencodedkey the X509 encoded key of the new friend user, encoded with userKey
     */
    public boolean addFriend(byte[] userKey, byte[] signedHash, byte[] userBencodedKey)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! key.isValidSignature(signedHash, userBencodedKey))
            return false;

        UserPublicKey friendKey = new UserPublicKey(userBencodedKey);

        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null || userMap.get(friendKey) == null)
                return false;
            if (userData.friends.contains(friendKey))
                return false;

            userData.friends.add(friendKey);
            return true; 
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param signedHash the SHA hash of encodedFragmentData, signed with the user private key 
     * @param encodedFragmentData fragment meta-data encoded with userKey
     */ 
    public boolean addFragment(byte[] userKey, byte[] signedHash, byte[] encodedFragmentData)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! key.isValidSignature(signedHash, encodedFragmentData))
            return false;

        byte[] hash = key.hash(encodedFragmentData);

        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null)
                return false;

            ByteArrayWrapper hashW = new ByteArrayWrapper(hash);
            if (userData.fragments.containsKey(hashW))
                return false;
            userData.fragments.put(hashW, new ByteArrayWrapper(encodedFragmentData));
            return true;
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param hash the hash of the fragment to be removed 
     * @param signedHash the SHA hash of hash, signed with the user private key 
     */ 
    public boolean removeFragment(byte[] userKey, byte[] signedHash, byte[] hash)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! key.isValidSignature(signedHash, hash))
            return false;

        synchronized(this)
        {
            UserData userData = userMap.get(key);

            if (userData == null)
                return false;
            return userData.fragments.remove(new ByteArrayWrapper(hash)) != null;
        }
    }

    /*
     * @param userKey X509 encoded key of user to be removed 
     * @param username to be removed 
     * @param signedHash the SHA hash of the bytes that make up username, signed with the user private key 
     *
     */
    public boolean removeUsername(byte[] userKey, byte[] signedHash, String username)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! Arrays.equals(key.hash(username),key.unsignMessage(signedHash)))
            return false;

        synchronized(this)
        {
            userPublicKeyToNameMap.remove(key);
            userMap.remove(key);
            return userNameToPublicKeyMap.remove(username) != null;
        }
    }

    /*
     * @param user the username whose friend list is being checked
     * @param the friend-username that is being sought in usernames friend-list
     */
    public synchronized  boolean hasFriend(String user, String friend)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(user);
        UserPublicKey friendKey = userNameToPublicKeyMap.get(friend);
        if (userKey == null || friendKey == null)
            return false;

        UserData userData = userMap.get(userKey);
        return userData.friends.contains(friendKey);
    }

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public Iterator<UserPublicKey> getFriends(byte[] userKey, byte[] signedHash)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        if (! key.isValidSignature(signedHash, userKey))
            return null;
        synchronized(this)
        {
            UserData userData = userMap.get(key);
            if (userData == null)
                return null;

            return Collections.unmodifiableCollection(userData.friends).iterator();
        }
    } 

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public Iterator<ByteArrayWrapper> getFragments(byte[] userKey, byte[] signedHash)
    {
        UserPublicKey key = new UserPublicKey(userKey);
        if (! key.isValidSignature(signedHash, userKey))
            return null;
        
        synchronized(this)
        {
            UserData userData = userMap.get(key);
            if (userData == null)
                return null;

            return Collections.unmodifiableCollection(userData.fragments.values()).iterator();
        }
    }

}