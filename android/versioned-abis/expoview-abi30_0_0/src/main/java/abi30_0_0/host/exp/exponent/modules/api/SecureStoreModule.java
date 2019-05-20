// Copyright 2015-present 650 Industries. All rights reserved.

package abi30_0_0.host.exp.exponent.modules.api;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import abi30_0_0.com.facebook.react.bridge.AssertionException;
import abi30_0_0.com.facebook.react.bridge.Promise;
import abi30_0_0.com.facebook.react.bridge.ReactApplicationContext;
import abi30_0_0.com.facebook.react.bridge.ReactContextBaseJavaModule;
import abi30_0_0.com.facebook.react.bridge.ReactMethod;
import abi30_0_0.com.facebook.react.bridge.ReadableMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

import host.exp.exponent.utils.ScopedContext;
import host.exp.expoview.Exponent;

public class SecureStoreModule extends ReactContextBaseJavaModule {
  private static final String TAG = SecureStoreModule.class.getSimpleName();

  private static final String SHARED_PREFERENCES_NAME = "SecureStore";
  private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

  private static final String ALIAS_PROPERTY = "keychainService";

  private static final String SCHEME_PROPERTY = "scheme";

  private ScopedContext mScopedContext;

  private @Nullable KeyStore mKeyStore;
  private AESEncrypter mAESEncrypter;
  private HybridAESEncrypter mHybridAESEncrypter;

  public SecureStoreModule(ReactApplicationContext reactContext, ScopedContext scopedContext) {
    super(reactContext);

    mScopedContext = scopedContext;

    mAESEncrypter = new AESEncrypter();
    mHybridAESEncrypter = new HybridAESEncrypter(mScopedContext, mAESEncrypter);
  }

  @Override
  public String getName() {
    return "ExponentSecureStore";
  }

  // NOTE: This currently doesn't remove the entry (if any) in the legacy shared preferences
  @ReactMethod
  public void setValueWithKeyAsync(@Nullable String value, String key, ReadableMap options, Promise promise) {
    try {
      setItemImpl(key, value, options, promise);
    } catch (Exception e) {
      Log.e(TAG, "Caught unexpected exception when writing to SecureStore", e);
      promise.reject("E_SECURESTORE_WRITE_ERROR", "An unexpected error occurred when writing to SecureStore", e);
    }
  }

  private void setItemImpl(String key, @Nullable String value, ReadableMap options, Promise promise) {
    if (key == null) {
      promise.reject("E_SECURESTORE_NULL_KEY", "SecureStore keys must not be null");
      return;
    }

    SharedPreferences prefs = mScopedContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);

    if (value == null) {
      boolean success = prefs.edit().putString(key, null).commit();
      if (success) {
        promise.resolve(null);
      } else {
        promise.reject("E_SECURESTORE_WRITE_ERROR", "Could not write a null value to SecureStore");
      }
      return;
    }

    JSONObject encryptedItem;
    try {
      KeyStore keyStore = getKeyStore();

      // Android API 23+ supports storing symmetric keys in the keystore and on older Android
      // versions we store an asymmetric key pair and use hybrid encryption. We store the scheme we
      // use in the encrypted JSON item so that we know how to decode and decrypt it when reading
      // back a value.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        SecretKeyEntry secretKeyEntry = getKeyEntry(SecretKeyEntry.class, mAESEncrypter, options);
        encryptedItem = mAESEncrypter.createEncryptedItem(value, keyStore, secretKeyEntry);
        encryptedItem.put(SCHEME_PROPERTY, AESEncrypter.NAME);
      } else {
        PrivateKeyEntry privateKeyEntry = getKeyEntry(PrivateKeyEntry.class, mHybridAESEncrypter, options);
        encryptedItem = mHybridAESEncrypter.createEncryptedItem(value, keyStore, privateKeyEntry);
        encryptedItem.put(SCHEME_PROPERTY, HybridAESEncrypter.NAME);
      }
    } catch (IOException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_IO_ERROR", "There was an I/O error loading the keystore for SecureStore", e);
      return;
    } catch (GeneralSecurityException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_ENCRYPT_ERROR", "Could not encrypt the value for SecureStore", e);
      return;
    } catch (JSONException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_ENCODE_ERROR", "Could not create an encrypted JSON item for SecureStore", e);
      return;
    }

    String encryptedItemString = encryptedItem.toString();
    if (encryptedItemString == null) {
      promise.reject("E_SECURESTORE_JSON_ERROR", "Could not JSON-encode the encrypted item for SecureStore");
      return;
    }

    boolean success = prefs.edit().putString(key, encryptedItemString).commit();
    if (success) {
      promise.resolve(null);
    } else {
      promise.reject("E_SECURESTORE_WRITE_ERROR", "Could not write encrypted JSON to SecureStore");
    }
  }

  @ReactMethod
  public void getValueWithKeyAsync(String key, ReadableMap options, Promise promise) {
    try {
      getItemImpl(key, options, promise);
    } catch (Exception e) {
      Log.e(TAG, "Caught unexpected exception when reading from SecureStore", e);
      promise.reject("E_SECURESTORE_READ_ERROR", "An unexpected error occurred when reading from SecureStore", e);
    }
  }

  private void getItemImpl(String key, ReadableMap options, Promise promise) {
    // We use a SecureStore-specific shared preferences file, which lets us do things like enumerate
    // its entries or clear all of them
    SharedPreferences prefs = getSharedPreferences();
    if (prefs.contains(key)) {
      readJSONEncodedItem(key, prefs, options, promise);
    } else {
      readLegacySDK20Item(key, options, promise);
    }
  }

  private void readJSONEncodedItem(String key, SharedPreferences prefs, ReadableMap options, Promise promise) {
    String encryptedItemString = prefs.getString(key, null);
    JSONObject encryptedItem;
    try {
      encryptedItem = new JSONObject(encryptedItemString);
    } catch (JSONException e) {
      Log.e(TAG, String.format("Could not parse stored value as JSON (key = %s, value = %s)", key, encryptedItemString), e);
      promise.reject("E_SECURESTORE_JSON_ERROR", "Could not parse the encrypted JSON item in SecureStore");
      return;
    }

    String scheme = encryptedItem.optString(SCHEME_PROPERTY);
    if (scheme == null) {
      Log.e(TAG, String.format("Stored JSON object is missing a scheme (key = %s, value = %s)", key, encryptedItemString));
      promise.reject("E_SECURESTORE_DECODE_ERROR", "Could not find the encryption scheme used for SecureStore item");
      return;
    }

    String value;
    try {
      switch (scheme) {
        case AESEncrypter.NAME:
          SecretKeyEntry secretKeyEntry = getKeyEntry(SecretKeyEntry.class, mAESEncrypter, options);
          value = mAESEncrypter.decryptItem(encryptedItem, secretKeyEntry);
          break;
        case HybridAESEncrypter.NAME:
          PrivateKeyEntry privateKeyEntry = getKeyEntry(PrivateKeyEntry.class, mHybridAESEncrypter, options);
          value = mHybridAESEncrypter.decryptItem(encryptedItem, privateKeyEntry);
          break;
        default:
          String message = String.format("The item for key \"%s\" in SecureStore has an unknown encoding scheme (%s)", key, scheme);
          Log.e(TAG, message);
          promise.reject("E_SECURESTORE_DECODE_ERROR", message);
          return;
      }
    } catch (IOException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_IO_ERROR", "There was an I/O error loading the keystore for SecureStore", e);
      return;
    } catch (GeneralSecurityException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_DECRYPT_ERROR", "Could not decrypt the item in SecureStore", e);
      return;
    } catch (JSONException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_DECODE_ERROR", "Could not decode the encrypted JSON item in SecureStore", e);
      return;
    }

    promise.resolve(value);
  }

  private void readLegacySDK20Item(String key, ReadableMap options, Promise promise) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mScopedContext);
    String encryptedItem = prefs.getString(key, null);

    // In the SDK20 scheme, we stored null and empty strings directly so we want to decode them the
    // same way, but we also want to return null if we didn't find any value at all; the developer
    // might be retrieving a value for a non-existent key.
    if (TextUtils.isEmpty(encryptedItem)) {
      promise.resolve(null);
      return;
    }

    String value;
    LegacySDK20Encrypter encrypter = new LegacySDK20Encrypter();
    try {
      KeyStore keyStore = getKeyStore();
      String keystoreAlias = encrypter.getKeyStoreAlias(options);

      if (!keyStore.containsAlias(keystoreAlias)) {
        promise.reject("E_SECURESTORE_DECRYPT_ERROR", "Could not find the keystore entry to decrypt the legacy item in SecureStore");
        return;
      }

      KeyStore.Entry keyStoreEntry = keyStore.getEntry(keystoreAlias, null);
      if (!(keyStoreEntry instanceof PrivateKeyEntry)) {
        promise.reject("E_SECURESTORE_DECRYPT_ERROR", "The keystore entry for the legacy item is not a private key entry");
        return;
      }

      value = encrypter.decryptItem(encryptedItem, (PrivateKeyEntry) keyStoreEntry);
    } catch (IOException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_IO_ERROR", "There was an I/O error loading the keystore for SecureStore", e);
      return;
    } catch (GeneralSecurityException e) {
      Log.w(TAG, e);
      promise.reject("E_SECURESTORE_DECRYPT_ERROR", "Could not decrypt the item in SecureStore", e);
      return;
    }

    promise.resolve(value);
  }

  @ReactMethod
  public void deleteValueWithKeyAsync(String key, ReadableMap options, Promise promise) {
    try {
      deleteItemImpl(key, options, promise);
    } catch (Exception e) {
      Log.e(TAG, "Caught unexpected exception when deleting from SecureStore", e);
      promise.reject("E_SECURESTORE_DELETE_ERROR", "An unexpected error occurred when deleting item from SecureStore", e);
    }
  }

  private void deleteItemImpl(String key, ReadableMap options, Promise promise) {
    boolean success = true;
    SharedPreferences prefs = getSharedPreferences();
    if (prefs.contains(key)) {
      success = prefs.edit().remove(key).commit() && success;
    }

    SharedPreferences legacyPrefs = PreferenceManager.getDefaultSharedPreferences(mScopedContext);
    if (legacyPrefs.contains(key)) {
      success = legacyPrefs.edit().remove(key).commit() && success;
    }

    if (success) {
      promise.resolve(null);
    } else {
      promise.reject("E_SECURESTORE_DELETE_ERROR", "Could not delete the item from SecureStore");
    }
  }

  /**
   * We use a shared preferences file that's scoped to both the experience and SecureStore. This
   * lets us easily list or remove all the entries for an experience.
   */
  private SharedPreferences getSharedPreferences() {
    return mScopedContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
  }

  private KeyStore getKeyStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
    if (mKeyStore == null) {
      KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
      keyStore.load(null);
      mKeyStore = keyStore;
    }
    return mKeyStore;
  }

  private <E extends KeyStore.Entry> E getKeyEntry(Class<E> keyStoreEntryClass,
                                                   KeyBasedEncrypter<E> encrypter,
                                                   ReadableMap options) throws IOException, GeneralSecurityException {
    KeyStore keyStore = getKeyStore();
    String keystoreAlias = encrypter.getKeyStoreAlias(options);

    E keyStoreEntry;
    if (!keyStore.containsAlias(keystoreAlias)) {
      keyStoreEntry = encrypter.initializeKeyStoreEntry(keyStore, options);
    } else {
      KeyStore.Entry entry = keyStore.getEntry(keystoreAlias, null);
      if (!keyStoreEntryClass.isInstance(entry)) {
        String message = String.format(
            "The entry for the keystore alias \"%s\" is not a %s",
            keystoreAlias, keyStoreEntryClass.getSimpleName());
        throw new KeyStoreException(message);
      }
      keyStoreEntry = keyStoreEntryClass.cast(entry);
    }

    return keyStoreEntry;
  }

  private interface KeyBasedEncrypter<E extends KeyStore.Entry> {
    String getKeyStoreAlias(ReadableMap options);

    E initializeKeyStoreEntry(KeyStore keyStore, ReadableMap options) throws
        GeneralSecurityException;

    JSONObject createEncryptedItem(String plaintextValue, KeyStore keyStore, E keyStoreEntry) throws
        GeneralSecurityException, JSONException;

    String decryptItem(JSONObject encryptedItem, E keyStoreEntry) throws
        GeneralSecurityException, JSONException;
  }

  /**
   * An encrypter that stores a symmetric key (AES) in the Android keystore. It generates a new IV
   * each time an item is written to prevent many-time pad attacks. The IV is stored with the
   * encrypted item.
   *
   * AES with GCM is supported on Android 10+ but storing an AES key in the keystore is supported
   * on only Android 23+. If you generate your own key instead of using the Android keystore (like
   * the hybrid encrypter does) you can use the encyption and decryption methods of this class.
   */
  private static class AESEncrypter implements KeyBasedEncrypter<SecretKeyEntry> {
    public static final String NAME = "aes";

    private static final String DEFAULT_ALIAS = "key_v1";
    private static final String AES_CIPHER = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE_BITS = 256;

    private static final String CIPHERTEXT_PROPERTY = "ct";
    private static final String IV_PROPERTY = "iv";
    private static final String GCM_AUTHENTICATION_TAG_LENGTH_PROPERTY = "tlen";

    @Override
    public String getKeyStoreAlias(ReadableMap options) {
      String baseAlias = options.hasKey(ALIAS_PROPERTY) ? options.getString(ALIAS_PROPERTY) : DEFAULT_ALIAS;
      return AES_CIPHER + ":" + baseAlias;
    }

    @Override
    @TargetApi(23)
    public KeyStore.SecretKeyEntry initializeKeyStoreEntry(KeyStore keyStore, ReadableMap options) throws GeneralSecurityException {
      String keystoreAlias = getKeyStoreAlias(options);
      int keyPurposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
      AlgorithmParameterSpec algorithmSpec = new KeyGenParameterSpec.Builder(keystoreAlias, keyPurposes)
          .setKeySize(AES_KEY_SIZE_BITS)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build();

      KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStore.getProvider());
      keyGenerator.init(algorithmSpec);

      // KeyGenParameterSpec stores the key when it is generated
      keyGenerator.generateKey();
      SecretKeyEntry keyStoreEntry = (SecretKeyEntry) keyStore.getEntry(keystoreAlias, null);
      if (keyStoreEntry == null) {
        throw new UnrecoverableEntryException("Could not retrieve the newly generated secret key entry");
      }

      return keyStoreEntry;
    }

    @Override
    public JSONObject createEncryptedItem(String plaintextValue, KeyStore keyStore, SecretKeyEntry secretKeyEntry) throws
        GeneralSecurityException, JSONException {

      SecretKey secretKey = secretKeyEntry.getSecretKey();
      Cipher cipher = Cipher.getInstance(AES_CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey);

      return createEncryptedItem(plaintextValue, cipher);
    }

    /* package */ JSONObject createEncryptedItem(String plaintextValue, Cipher cipher) throws
        GeneralSecurityException, JSONException {

      byte[] plaintextBytes = plaintextValue.getBytes(StandardCharsets.UTF_8);
      byte[] ciphertextBytes = cipher.doFinal(plaintextBytes);
      String ciphertext = Base64.encodeToString(ciphertextBytes, Base64.DEFAULT);

      GCMParameterSpec gcmSpec = cipher.getParameters().getParameterSpec(GCMParameterSpec.class);
      String ivString = Base64.encodeToString(gcmSpec.getIV(), Base64.DEFAULT);
      int authenticationTagLength = gcmSpec.getTLen();

      return new JSONObject()
          .put(CIPHERTEXT_PROPERTY, ciphertext)
          .put(IV_PROPERTY, ivString)
          .put(GCM_AUTHENTICATION_TAG_LENGTH_PROPERTY, authenticationTagLength);
    }

    @Override
    public String decryptItem(JSONObject encryptedItem, SecretKeyEntry secretKeyEntry) throws
        GeneralSecurityException, JSONException {

      String ciphertext = encryptedItem.getString(CIPHERTEXT_PROPERTY);
      String ivString = encryptedItem.getString(IV_PROPERTY);
      int authenticationTagLength = encryptedItem.getInt(GCM_AUTHENTICATION_TAG_LENGTH_PROPERTY);
      byte[] ciphertextBytes = Base64.decode(ciphertext, Base64.DEFAULT);
      byte[] ivBytes = Base64.decode(ivString, Base64.DEFAULT);

      GCMParameterSpec gcmSpec = new GCMParameterSpec(authenticationTagLength, ivBytes);
      Cipher cipher = Cipher.getInstance(AES_CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, secretKeyEntry.getSecretKey(), gcmSpec);
      byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);

      return new String(plaintextBytes, StandardCharsets.UTF_8);
    }
  }

  /**
   * An AES encrypter that works with Android L (API 22) and below, which cannot store symmetric
   * keys in the keystore. We store an asymmetric key pair (RSA) in the keystore, which is used to
   * securely encrypt a symmetric key (AES) that we use to encrypt the data.
   *
   * The item we store includes the ciphertext (encrypted with AES), the AES IV, and the encrypted
   * symmetric key (which requires the keystore's asymmetric private key to decrypt).
   *
   * https://crypto.stackexchange.com/questions/14/how-can-i-use-asymmetric-encryption-such-as-rsa-to-encrypt-an-arbitrary-length
   *
   * When we drop support for Android API 22, we can remove the write paths but need to keep the
   * read paths for phones that still have hybrid-encrypted values on disk.
   */
  private static class HybridAESEncrypter implements KeyBasedEncrypter<PrivateKeyEntry> {
    public static final String NAME = "hybrid";

    private static final String DEFAULT_ALIAS = "key_v1";
    private static final String RSA_CIPHER = "RSA/None/PKCS1Padding";
    // BouncyCastle/SpongyCastle throw an exception on older Android versions when accessing RSA key
    // pairs generated using the keystore
    private static final String RSA_CIPHER_LEGACY_PROVIDER = "AndroidOpenSSL";
    private static final int X509_SERIAL_NUMBER_LENGTH_BITS = 20 * 8;

    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_AUTHENTICATION_TAG_LENGTH_BITS = 128;

    private static final String ENCRYPTED_SECRET_KEY_PROPERTY = "esk";

    private Context mContext;
    private AESEncrypter mAESEncrypter;
    private SecureRandom mSecureRandom;

    public HybridAESEncrypter(Context context, AESEncrypter aesEncrypter) {
      mContext = context;
      mAESEncrypter = aesEncrypter;
      mSecureRandom = new SecureRandom();
    }

    @Override
    public String getKeyStoreAlias(ReadableMap options) {
      String baseAlias = options.hasKey(ALIAS_PROPERTY) ? options.getString(ALIAS_PROPERTY) : DEFAULT_ALIAS;
      return RSA_CIPHER + ":" + baseAlias;
    }

    @Override
    @SuppressWarnings("deprecation")
    public KeyStore.PrivateKeyEntry initializeKeyStoreEntry(KeyStore keyStore, ReadableMap options) throws GeneralSecurityException {
      String keystoreAlias = getKeyStoreAlias(options);
      // See https://tools.ietf.org/html/rfc1779#section-2.3 for the DN grammar
      String escapedCommonName = '"' + keystoreAlias.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
      AlgorithmParameterSpec algorithmSpec = new KeyPairGeneratorSpec.Builder(mContext)
          .setAlias(keystoreAlias)
          .setSubject(new X500Principal("CN=" + escapedCommonName + ", OU=SecureStore"))
          .setSerialNumber(new BigInteger(X509_SERIAL_NUMBER_LENGTH_BITS, mSecureRandom))
          .setStartDate(new Date(0))
          .setEndDate(new Date(Long.MAX_VALUE))
          .build();

      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, keyStore.getProvider());
      keyPairGenerator.initialize(algorithmSpec);

      // Before Android M, only the AndroidOpenSSL provider doesn't throw an exception when
      // generating the key pair. Since we give the Spongy Castle provider the highest priority, we
      // need to tell Android not to use it here. Unfortunately generateKeyPair() generates a
      // certificate without passing in an explicit provider, which means Android chooses a provider
      // based on priority and uses Spongy Castle: https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r7/keystore/java/android/security/AndroidKeyPairGenerator.java#133
      // So, we temporarily remove Spongy Castle, generate the key pair, and then add it back.
      Provider spongyCastleProvider = Exponent.getBouncyCastleProvider();
      // Security providers are 1-indexed
      int spongyCastleProviderPosition = Arrays.asList(Security.getProviders()).indexOf(spongyCastleProvider) + 1;
      if (spongyCastleProviderPosition > 0) {
        Security.removeProvider(spongyCastleProvider.getName());
      }
      try {
        // KeyPairGenerator stores the keys and self-signed certificates when they are generated
        keyPairGenerator.generateKeyPair();
      } finally {
        if (spongyCastleProviderPosition > 0) {
          Security.insertProviderAt(spongyCastleProvider, spongyCastleProviderPosition);
        }
      }

      KeyStore.PrivateKeyEntry keyStoreEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keystoreAlias, null);
      if (keyStoreEntry == null) {
        throw new UnrecoverableEntryException("Could not retrieve the newly generated private key entry");
      }

      return keyStoreEntry;
    }

    @Override
    public JSONObject createEncryptedItem(String plaintextValue, KeyStore keyStore, KeyStore.PrivateKeyEntry privateKeyEntry) throws
        GeneralSecurityException, JSONException {

      // Generate the IV and symmetric key with which we encrypt the value
      byte[] ivBytes = new byte[GCM_IV_LENGTH_BYTES];
      mSecureRandom.nextBytes(ivBytes);

      KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
      keyGenerator.init(AESEncrypter.AES_KEY_SIZE_BITS);
      SecretKey secretKey = keyGenerator.generateKey();

      // Encrypt the value with the symmetric key. We need to specify the GCM parameters since the
      // our secret key isn't tied to the keystore and the cipher can't use the secret key to
      // generate the parameters.
      AlgorithmParameterSpec gcmSpec = new GCMParameterSpec(GCM_AUTHENTICATION_TAG_LENGTH_BITS, ivBytes);
      Cipher aesCipher = Cipher.getInstance(AESEncrypter.AES_CIPHER);
      aesCipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
      JSONObject encryptedItem = mAESEncrypter.createEncryptedItem(plaintextValue, aesCipher);

      // Ensure the IV in the encrypted item matches our generated IV
      String ivString = encryptedItem.getString(AESEncrypter.IV_PROPERTY);
      String expectedIVString = Base64.encodeToString(ivBytes, Base64.DEFAULT);
      if (!ivString.equals(expectedIVString)) {
        Log.e(TAG, String.format("HybridAESEncrypter generated two different IVs: %s and %s", expectedIVString, ivString));
        throw new AssertionException("HybridAESEncrypter must store the same IV as the one used to parameterize the secret key");
      }

      // Encrypt the symmetric key with the asymmetric public key
      byte[] secretKeyBytes = secretKey.getEncoded();
      Cipher cipher = getRSACipher();
      cipher.init(Cipher.ENCRYPT_MODE, privateKeyEntry.getCertificate());
      byte[] encryptedSecretKeyBytes = cipher.doFinal(secretKeyBytes);
      String encryptedSecretKeyString = Base64.encodeToString(encryptedSecretKeyBytes, Base64.DEFAULT);

      // Store the encrypted symmetric key in the encrypted item
      return encryptedItem.put(ENCRYPTED_SECRET_KEY_PROPERTY, encryptedSecretKeyString);
    }

    @Override
    public String decryptItem(JSONObject encryptedItem, KeyStore.PrivateKeyEntry privateKeyEntry) throws
        GeneralSecurityException, JSONException {

      // Decrypt the encrypted symmetric key
      String encryptedSecretKeyString = encryptedItem.getString(ENCRYPTED_SECRET_KEY_PROPERTY);
      byte[] encryptedSecretKeyBytes = Base64.decode(encryptedSecretKeyString, Base64.DEFAULT);

      Cipher cipher = getRSACipher();
      cipher.init(Cipher.DECRYPT_MODE, privateKeyEntry.getPrivateKey());
      byte[] secretKeyBytes = cipher.doFinal(encryptedSecretKeyBytes);
      SecretKey secretKey = new SecretKeySpec(secretKeyBytes, KeyProperties.KEY_ALGORITHM_AES);

      // Decrypt the value with the symmetric key
      KeyStore.SecretKeyEntry secretKeyEntry = new KeyStore.SecretKeyEntry(secretKey);
      return mAESEncrypter.decryptItem(encryptedItem, secretKeyEntry);
    }

    private Cipher getRSACipher() throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException {
      return (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
          ? Cipher.getInstance(RSA_CIPHER, RSA_CIPHER_LEGACY_PROVIDER)
          : Cipher.getInstance(RSA_CIPHER);
    }

  }

  /**
   * A legacy encrypter that supports only RSA decryption for values written with SDK 20's
   * implementation of SecureStore.
   *
   * Consider removing this after it's likely users have migrated all legacy entries (SDK ~27).
   */
  private static class LegacySDK20Encrypter {
    private static final String RSA_CIPHER = "RSA/ECB/PKCS1Padding";
    private static final String DEFAULT_ALIAS = "MY_APP";

    public String getKeyStoreAlias(ReadableMap options) {
      return options.hasKey(ALIAS_PROPERTY) ? options.getString(ALIAS_PROPERTY) : DEFAULT_ALIAS;
    }

    public String decryptItem(String encryptedItem, KeyStore.PrivateKeyEntry privateKeyEntry) throws GeneralSecurityException {
      byte[] ciphertextBytes = Base64.decode(encryptedItem, Base64.DEFAULT);

      Cipher cipher = Cipher.getInstance(RSA_CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, privateKeyEntry.getPrivateKey());
      byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);

      return new String(plaintextBytes, StandardCharsets.UTF_8);
    }
  }
}
