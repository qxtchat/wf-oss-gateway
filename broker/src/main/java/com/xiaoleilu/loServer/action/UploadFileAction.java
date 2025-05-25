/*
 * This file is part of the Wildfire Chat package.
 * (c) Heavyrain2012 <heavyrain.lee@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package com.xiaoleilu.loServer.action;

import com.xiaoleilu.loServer.ServerSetting;
import com.xiaoleilu.loServer.annotation.HttpMethod;
import com.xiaoleilu.loServer.annotation.RequireAuthentication;
import com.xiaoleilu.loServer.annotation.Route;
import com.xiaoleilu.loServer.handler.*;
import io.moquette.server.config.MediaServerConfig;
import io.moquette.server.config.IConfig;
import io.moquette.server.Server;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.util.internal.StringUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.*;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

import static com.xiaoleilu.loServer.handler.HttpResponseHelper.getFileExt;

@Route("/fs")
@HttpMethod("POST")
@RequireAuthentication
public class UploadFileAction extends Action {
    private static final Logger logger = LoggerFactory.getLogger(UploadFileAction.class);
    private static final HttpDataFactory factory = new DefaultHttpDataFactory(false);
    public static boolean USE_SM4 = false;

    private long fileSize = 0;
    private String bucketName = null;
    private String userSecret = null;
    private String fileName = null;
    private File saveFile = null;

    // MinIO配置参数（建议从配置文件读取）
    private static String MINIO_ENDPOINT = null;
    private static String MINIO_ACCESS_KEY = null;
    private static String MINIO_SECRET_KEY = null;
    private static MinioClient minioClient = null;
    private ByteArrayOutputStream minioBuffer = null;

    private static String SECRET_KEY = null; // 从配置文件读取

    static {
        // minioClient = MinioClient.builder()
        //     .endpoint(MINIO_ENDPOINT)
        //     .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
        //     .build();
    }

    private static void ensureMinioConfigLoaded() {
        if (MINIO_ENDPOINT == null || MINIO_ACCESS_KEY == null || MINIO_SECRET_KEY == null) {
            IConfig config = Server.defaultConfig();
            MINIO_ENDPOINT = config.getProperty("minio.endpoint", "http://localhost:9000");
            MINIO_ACCESS_KEY = config.getProperty("minio.access_key", "minioadmin");
            MINIO_SECRET_KEY = config.getProperty("minio.secret_key", "minioadmin");
            minioClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
        }
    }

    private static void ensureSecretKeyLoaded() {
        if (SECRET_KEY == null) {
            IConfig config = Server.defaultConfig();
            SECRET_KEY = config.getProperty("go.token_secret", "wfossgatewaydemo");
        }
    }

    @Override
    public boolean action(Request r, Response response) {
        if (r.getNettyRequest() instanceof FullHttpRequest) {
            response.setHeader("Access-Control-Allow-Origin", "*");

            FullHttpRequest request = (FullHttpRequest) r.getNettyRequest();
            String requestId = UUID.randomUUID().toString().replace("-", "");
            logger.info("HttpFileServerHandler received a request: method=" + request.getMethod() + ", uri=" + request.getUri() + ", requestId=" + requestId);

            if (!request.getDecoderResult().isSuccess()) {
                logger.warn("http decode failed!");
                response.setStatus(HttpResponseStatus.BAD_REQUEST);
                response.setContent("http decode failed");
                return true;
            }
            multipartUpload(request, requestId, response);
        }
        return true;
    }

    private void multipartUpload(FullHttpRequest request, String requestId, Response response) {
        HttpPostRequestDecoder decoder = null;
        try {
            decoder = new HttpPostRequestDecoder(factory, request);
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
            logger.error("Failed to decode file data!", e1);
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
            response.setContent("Failed to decode file data!");
            return;
        }

        if (decoder != null) {
            if (request instanceof HttpContent) {
                HttpContent chunk = (HttpContent) request;
                try {
                    decoder.offer(chunk);
                } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
                    logger.warn("BAD_REQUEST, Failed to decode file data");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("Failed to decode file data!");
                    return;
                }
                fileSize = Long.parseLong(request.headers().get("x-wfc-size"));
                readHttpDataChunkByChunk(response, decoder, requestId, HttpHeaders.isKeepAlive(request));
            } else {
                logger.warn("BAD_REQUEST, Not a http request");
                response.setStatus(HttpResponseStatus.BAD_REQUEST);
                response.setContent("Not a http request");
            }
        }
    }

    /**
     * readHttpDataChunkByChunk
     */
    private void readHttpDataChunkByChunk(Response response, HttpPostRequestDecoder decoder, String requestId, boolean isKeepAlive) {
        try {
            while (decoder.hasNext()) {
                InterfaceHttpData data = decoder.next();
                if (data != null) {
                    try {
                        if(!writeFileUploadData(data, response, requestId, isKeepAlive)) {
                            break;
                        }
                    } finally {
                        data.release();
                    }
                }
            }
        } catch (Exception e) {
            logger.info("chunk end");
        }
    }

    /**
     * writeFileUploadData
     */
    private boolean writeFileUploadData(InterfaceHttpData data, Response response, String requestId, boolean isKeepAlive) {
        try {
            if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                FileUpload fileUpload = (FileUpload) data;

                String remoteFileName = fileUpload.getFilename();
                long remoteFileSize = fileUpload.length();

                if(bucketName == null) {
                    logger.warn("Not authenticated!");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("Not authenticated!");
                    return false;
                }

                if (StringUtil.isNullOrEmpty(remoteFileName)) {
                    logger.warn("remoteFileName is empty!");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("file name is empty");
                    return false;
                }

                if (StringUtil.isNullOrEmpty(requestId)) {
                    logger.warn("requestId is empty!");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("requestId is empty!");
                    return false;
                }

                if (remoteFileSize > 200 * 1024 * 1024) {
                    logger.warn("file over limite!(" + remoteFileSize + ")");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("file over limite!");
                    return false;
                }

                String remoteFileExt = "";
                if (remoteFileName.lastIndexOf(".") == -1) {
                    remoteFileExt = "octetstream";
                    remoteFileName = remoteFileName + "." + remoteFileExt;
                } else {
                    remoteFileExt = getFileExt(remoteFileName);
                }

                if (StringUtil.isNullOrEmpty(remoteFileExt) || remoteFileExt.equals("ing")) {
                    logger.warn("Invalid file extention name");
                    response.setStatus(HttpResponseStatus.BAD_REQUEST);
                    response.setContent("Invalid file extention name");
                    return false;
                }

                fileName = StringUtil.isNullOrEmpty(remoteFileName) ? requestId : remoteFileName;


                boolean useSM4 = USE_SM4;
                byte[] aesKey = convertUserKey(userSecret);
                Cipher cipher;
                if (useSM4){
                    cipher = getSM4Cipher(aesKey, aesKey, false);
                } else {
                    cipher = getCipher(aesKey, false);
                }

                CipherInputStream cis = new CipherInputStream(new ByteBufInputStream(fileUpload.getByteBuf()), cipher);
                logger.info("before write the file");
                boolean isError = false;
                int offset = 0;

                if(!beforeData()){
                    response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    response.setContent("服务器错误");
                    return false;
                }

                while (true) {
                    try {
                        byte[] tmpBuf = new byte[1024];
                        int readableBytesSize = cis.read(tmpBuf);
                        if(readableBytesSize > 0) {
                            onData(offset, tmpBuf, readableBytesSize);
                            offset += readableBytesSize;
                        } else {
                            fileUpload.release();
                            afterData();
                            break;
                        }
                    } catch (Exception e) {
                        logger.error("save thunckData error!", e);
                        fileUpload.release();
                        response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        response.setContent("服务器错误：" + e.getMessage());
                        isError = true;

                        return false;
                    } finally {
                        if (isError && saveFile != null) {
                            saveFile.delete();
                        }
                    }
                }

                response.setStatus(HttpResponseStatus.OK);
                response.setContent("{\"key\":\"" + fileName + "\"}");
            } else if(data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                Attribute attribute = (Attribute)data;
                if(attribute.getName().equals("token")) {
                    String token = attribute.getValue();

                    getBucketFromProxyToken(token, MediaServerConfig.MEDIA_ACCESS_KEY, MediaServerConfig.MEDIA_SECRET_KEY);
                    if(bucketName == null){
                        logger.error("无效的token!");
                        response.setStatus(HttpResponseStatus.BAD_REQUEST);
                        response.setContent("无效的token");
                        return false;
                    }
                }
            } else {
                response.setStatus(HttpResponseStatus.BAD_REQUEST);
                response.setContent("无效请求");
                return false;
            }
        } catch (Exception e) {
            logger.error("writeHttpData error!", e);
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setContent("服务器错误：" + e.getMessage());
            return false;
        }
        return true;
    }

    //token已经校验，用户密钥已经取到，可以接收数据了
    public boolean beforeData() {
        ensureMinioConfigLoaded();
        minioBuffer = new ByteArrayOutputStream();
        return true;
    }

    //可以在这里进行文件上传，pos为收到文件内容的偏移量，data为收到解密过的数据，length为数据的长度， dataSize为总长度。
    public void onData(long pos, byte[] data, int length) throws Exception {
        minioBuffer.write(data, 0, length);
    }

    //已经完成所有数据的接收，如果想要修改返回客户端的Key值，在这个函数里修改fileName即可
    public void afterData() {
        ensureMinioConfigLoaded();
        try {
            // 检查bucket是否存在，不存在则创建
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                logger.info("MinIO bucket created: {}", bucketName);
            }
            byte[] fileBytes = minioBuffer.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(bais, fileBytes.length, -1)
                    .contentType("application/octet-stream")
                    .build()
            );
            logger.info("Upload to MinIO success: {}/{}", bucketName, fileName);
        } catch (MinioException e) {
            logger.error("MinIO upload error", e);
            throw new RuntimeException("MinIO upload error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("MinIO upload error", e);
            throw new RuntimeException("MinIO upload error: " + e.getMessage());
        } finally {
            if (minioBuffer != null) {
                try { minioBuffer.close(); } catch (Exception ignore) {}
                minioBuffer = null;
            }
        }
    }

    //解密文件内容相关
    private static byte[] convertUserKey(String userKey) {
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (userKey.charAt(i) & 0xFF);
        }
        return key;
    }

    //SM4
    static {
        Security.addProvider(new BouncyCastleProvider());
    }
    public static final String ALGORITHM_NAME = "SM4";
    public static final String ALGORITHM_NAME_CBC_PADDING = "SM4/CBC/PKCS5Padding";

    private static Cipher getAESCipher(byte[] aesKey, boolean encrypt) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        SecretKeySpec skeySpec = new SecretKeySpec(aesKey, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");//"算法/模式/补码方式"
        IvParameterSpec iv = new IvParameterSpec(aesKey);//使用CBC模式，需要一个向量iv，可增加加密算法的强度
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, skeySpec, iv);
        return cipher;
    }

    private static Cipher getSM4Cipher(byte[] aesKey, byte[] iv, boolean encrypt) throws NoSuchAlgorithmException, NoSuchPaddingException, NoSuchProviderException, InvalidAlgorithmParameterException, InvalidKeyException {
        Cipher cipher = Cipher.getInstance(ALGORITHM_NAME_CBC_PADDING, BouncyCastleProvider.PROVIDER_NAME);
        Key sm4Key = new SecretKeySpec(aesKey, ALGORITHM_NAME);
        IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, sm4Key, ivParameterSpec);
        return cipher;
    }

    private static Cipher getCipher(byte[] aesKey, boolean encrypt) throws NoSuchAlgorithmException, NoSuchPaddingException, NoSuchProviderException, InvalidAlgorithmParameterException, InvalidKeyException {
        Cipher cipher;
        if(USE_SM4) {
            cipher = getSM4Cipher(aesKey, aesKey, encrypt);
        } else {
            cipher = getAESCipher(aesKey, encrypt);
        }
        return cipher;
    }

    //Token相关接口
    public static String decryptDES(String decryptString, String encryptPassword) throws Exception {
        byte[] iv = { 1, 2, 3, 4, 5, 6, 7, 8 };
        if(encryptPassword.length() > 8) {
            encryptPassword = encryptPassword.substring(0, 8);
        }

        byte[] byteMi = Base64.getDecoder().decode(decryptString);
        IvParameterSpec zeroIv = new IvParameterSpec(iv);
        SecretKeySpec key = new SecretKeySpec(encryptPassword.getBytes(), "DES");
        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, zeroIv);
        byte decryptedData[] = cipher.doFinal(byteMi);

        return new String(decryptedData);
    }

    public void getBucketFromProxyToken(String token, String appid, String secret) {
        try {
            String signKey = decryptDES(token, secret);
            String[] ss = signKey.split("\\|");
            if(ss.length == 5) {
                String sId = ss[0];
                String sts = ss[1];
                String bucket = ss[4];
                if(sId.equals(appid)) {
                    long ts = Long.parseLong(sts);
                    if((System.currentTimeMillis() - ts) < 180000) {
                        String[] bucketParts = bucket.split("-");
                        if (bucketParts.length > 1) {
                            String ip = bucketParts[0];
                            String userId = ss[3];
                            String clientId = ss[2];
                            String baseUrl = "https://" + ip + ":2448/getToken";
                            String us = fetchSecretFromGoService(baseUrl, userId, clientId);
                            if(us != null) {
                                bucketName = bucket;
                                userSecret = us;
                            } else {
                                System.out.println("get user secret failure");
                            }
                            return;
                        } else {
                            System.out.println("invalid bucket format");
                        }
                    } else {
                        System.out.println("time expired");
                    }
                } else {
                    System.out.println("appid incorrect");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return;
    }

    private String fetchSecretFromGoService(String baseUrl, String userId, String clientId) {
        try {
            ensureSecretKeyLoaded();
            long timestamp = System.currentTimeMillis() / 1000;
            String tsStr = String.valueOf(timestamp);
            String sign = sha256(userId + clientId + tsStr + SECRET_KEY);
            String urlStr = baseUrl + "?userId=" + URLEncoder.encode(userId, "UTF-8") +
                    "&clientId=" + URLEncoder.encode(clientId, "UTF-8") +
                    "&timestamp=" + tsStr +
                    "&sign=" + sign;
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String sha256(String str) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(str.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if(hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch(Exception ex){
            throw new RuntimeException(ex);
        }
    }
}
