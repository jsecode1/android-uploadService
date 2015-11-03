package com.alexbbb.uploadservice;

/**
 * Created by fxm on 15-11-3.
 */
public interface ICipher {

    public byte[] encrypt(byte[] plainTextBytes);

    public byte[] decrypt(byte[] cipherTextBytes);
}
