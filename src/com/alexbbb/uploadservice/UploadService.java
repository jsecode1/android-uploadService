package com.alexbbb.uploadservice;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

import android.app.IntentService;
import android.app.NotificationManager;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.util.Log;

/**
 * Service to upload files as a multi-part form data in background using HTTP POST
 * with notification center progress display.
 *
 * @author alexbbb (Alex Gotev)
 */
public class UploadService extends IntentService {

    private static final String SERVICE_NAME = UploadService.class.getName();

    private static final int BUFFER_SIZE = 4096;
    private static final String NEW_LINE = "\r\n";
    private static final String TWO_HYPHENS = "--";

    protected static final String ACTION_UPLOAD = "com.alexbbb.uploadservice.action.upload";
    protected static final String PARAM_NOTIFICATION_CONFIG = "notificationConfig";
    protected static final String PARAM_PREPAIR_UPLOAD_URL = "prepairuploadurl";
    protected static final String PARAM_URL = "url";
    protected static final String PARAM_FILES = "files";
    protected static final String PARAM_REQUEST_HEADERS = "requestHeaders";
    protected static final String PARAM_REQUEST_PARAMETERS = "requestParameters";
    protected static final String PARAM_PREPARI_URL = "prepair_url";

    public static final String BROADCAST_ACTION = "com.alexbbb.uploadservice.broadcast.status";
    public static final String STATUS = "status";
    public static final int STATUS_IN_PROGRESS = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_ERROR = 3;
    public static final String PROGRESS = "progress";
    public static final String ERROR_EXCEPTION = "errorException";
    public static final String SERVER_RESPONSE_CODE = "serverResponseCode";
    public static final String SERVER_RESPONSE_MESSAGE = "serverResponseMessage";
    public static final String SERVER_RESPONSE_BODY = "serverResponseBody";
    public static final String UPLOADING_FILE = "uploadingfile";

    public static int maxTransportTimes = 3;//尝试次数

    public static ICipher mCiphertool = null;

    private NotificationManager notificationManager;
    private Builder notification;
    private UploadNotificationConfig notificationConfig;
    private int lastPublishedProgress;

    private static List<UploadRequest> mCuerrentUploadFileTaskList = new ArrayList<UploadRequest>(0);

    //private static Map<String, Boolean> mFileUploadStateMap = new HashMap<String, Boolean>(0);

    private static int fileCount = 0;

    private static List<String> mFileUploadSuspendList = new ArrayList<String>(0);

    private int fileHasUploadSize = 0;

    private String uploadingFileName;

    /**
     * Utility method that creates the intent that starts the background
     * file upload service.
     *
     * @param task object containing the upload request
     * @throws IllegalArgumentException if one or more arguments passed are invalid
     * @throws MalformedURLException    if the server URL is not valid
     */
    public static void startUpload(final UploadRequest task)
            throws IllegalArgumentException,
            MalformedURLException {
        if (task == null) {
            throw new IllegalArgumentException("Can't pass an empty task!");
        } else {
            task.validate();

            final Intent intent = new Intent(UploadService.class.getName());

            intent.setAction(ACTION_UPLOAD);
            intent.putExtra(PARAM_NOTIFICATION_CONFIG, task.getNotificationConfig());
            intent.putExtra(PARAM_URL, task.getServerUrl());
            intent.putExtra(PARAM_PREPARI_URL, task.getPrepairUploadUrl());
            intent.putExtra(PARAM_PREPAIR_UPLOAD_URL, task.getPrepairUploadUrl());
            intent.putParcelableArrayListExtra(PARAM_FILES, task.getFilesToUpload());
            intent.putParcelableArrayListExtra(PARAM_REQUEST_HEADERS, task.getHeaders());
            intent.putParcelableArrayListExtra(PARAM_REQUEST_PARAMETERS, task.getParameters());

            mCuerrentUploadFileTaskList.add(task);

            task.getContext().startService(intent);
        }
    }

    public static List<UploadRequest> getCuerrentUploadFileTaskList() {
        return mCuerrentUploadFileTaskList;
    }

    /**
     * @param task
     * @param isSuspend true-暂停上传 false-正在上传
     */
    public void setTaskSuspendList(UploadRequest task, boolean isSuspend) {
        //mFileUploadStateMap.put(task.getFilesToUpload().get(0).getFileName(), isSuspend);
        mFileUploadSuspendList.add(task.getFilesToUpload().get(0).getFileName());
    }

    public void removeTaskFromSuspendList(UploadRequest task, boolean isSuspend) {
        //mFileUploadStateMap.put(task.getFilesToUpload().get(0).getFileName(), isSuspend);
        mFileUploadSuspendList.remove(task.getFilesToUpload().get(0).getFileName());
    }

    public UploadService() {
        super(SERVICE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notification = new NotificationCompat.Builder(this);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();

            if (ACTION_UPLOAD.equals(action)) {
                notificationConfig = intent.getParcelableExtra(PARAM_NOTIFICATION_CONFIG);
                final String url = intent.getStringExtra(PARAM_URL);
                final String prepairUploadUrl = intent.getStringExtra(PARAM_PREPARI_URL);
                final ArrayList<FileToUpload> files = intent.getParcelableArrayListExtra(PARAM_FILES);
                final ArrayList<NameValue> headers = intent.getParcelableArrayListExtra(PARAM_REQUEST_HEADERS);
                final ArrayList<NameValue> parameters = intent.getParcelableArrayListExtra(PARAM_REQUEST_PARAMETERS);

                lastPublishedProgress = 0;
                int currentTimes = 0;

                try {
                    createNotification();
                    while (true) {
                        try {
                            fileCount++;
                            prepairUpload(prepairUploadUrl, files, headers, parameters);
                            handleFileUpload(url, files, headers, parameters);
                            break;
                        } catch (Exception exception) {
                            if (currentTimes == (maxTransportTimes - 1)) {
                                throw exception;
                            }
                        }
                        currentTimes++;
                    }
                } catch (Exception exception) {
                    broadcastError(exception);
                }
            }
        }
    }

    private void prepairUpload(final String url,
                               final ArrayList<FileToUpload> filesToUpload,
                               final ArrayList<NameValue> requestHeaders,
                               final ArrayList<NameValue> requestParameters)
            throws IOException {
        HttpURLConnection conn = null;
        OutputStream requestStream = null;
        ByteArrayOutputStream baos = null;
        InputStream is = null;

        //ByteArrayOutputStream os = new ByteArrayOutputStream();

        try {
            conn = getMultipartHttpURLConnection(url, null);

            setRequestHeaders(conn, requestHeaders);

            requestStream = conn.getOutputStream();

            /*//filesToUpload.size() != 1只传一个文件时不设置boundaryBytes及requestParameters(Form值)
            if(filesToUpload.size() != 1) {
                setRequestParameters(requestStream, requestParameters, boundaryBytes);
            }

            uploadFiles(requestStream, filesToUpload, boundaryBytes);

            if(filesToUpload.size() != 1) {
                final byte[] trailer = getTrailerBytes(boundary);
                requestStream.write(trailer, 0, trailer.length);
            }*/

            StringBuilder eventIdSb = buildEventId(filesToUpload);

            StringBuilder sb = new StringBuilder(0);
            sb.append("{");
            sb.append("\"EVENTID\":\"");
            sb.append(eventIdSb);
            sb.append("\",\"FILESIZE\":\"");
            sb.append(filesToUpload.get(0).getFile().length());
            sb.append("\",\"MD5\":\"");
            sb.append(getMD5(filesToUpload.get(0).getStream()));
            sb.append("\"}");

            byte[] datas = sb.toString().getBytes("utf-8");

            if(mCiphertool != null){
                datas = mCiphertool.encrypt(datas);
            }

            requestStream.write(datas);

            final int serverResponseCode = conn.getResponseCode();
            final String serverResponseMessage = conn.getResponseMessage();

//            StringBuilder response = new StringBuilder();
//            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//                BufferedReader input = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"), 8192);
//                String strLine = null;
//                while ((strLine = input.readLine()) != null) {
//                    response.append(strLine);
//                }
//                input.close();
//            }

            int length = 8192;
            datas = new byte[length];

            is = conn.getInputStream();
            baos = new ByteArrayOutputStream(0);

            while ((length = is.read(datas, 0, length)) > 0) {
                baos.write(datas, 0, length);
            }
            datas = baos.toByteArray();
            baos.close();
            is.close();

            if(mCiphertool != null){
                datas = mCiphertool.decrypt(datas);
            }

            StringBuilder response = new StringBuilder();
            response.append(new String(datas, "utf-8"));
            String str = new String(datas, "utf-8");
            System.out.print(str);

            String tag = "\"UPLOADBYTES\":\"";
            String resultStr = response.toString().toUpperCase();
            int index = resultStr.indexOf(tag) + tag.length();
            String uploadSizeStr = resultStr.substring(index);
            index = uploadSizeStr.indexOf("\"");
            uploadSizeStr = uploadSizeStr.substring(0, index);

            fileHasUploadSize = Integer.valueOf(uploadSizeStr);

            System.out.println("SERVER RESPONSE BODY " + response);
            for (Map.Entry<String, List<String>> k : conn.getHeaderFields().entrySet()) {
                for (String v : k.getValue()) {
                    System.out.println(k.getKey() + ":" + v);
                }
            }
            //broadcastCompleted(serverResponseCode, serverResponseMessage, response.toString());

        } finally {
            if(baos != null){
                try {
                    baos.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            if(is != null){
                try {
                    is.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            closeOutputStream(requestStream);
            closeConnection(conn);
        }
    }

    /**
     * @return MD5 hash of InputStream.
     */
    public static String getMD5(InputStream in) {
        if (in != null) {
            try {
                MessageDigest digester = MessageDigest.getInstance("MD5");
                byte[] bytes = new byte[8192];
                int byteCount;
                while ((byteCount = in.read(bytes)) > 0) {
                    digester.update(bytes, 0, byteCount);
                }
                byte[] digest = digester.digest();

                if (digest != null) {
                    return bufferToHex(digest);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return null;
    }

    /**
     * @return HEX buffer representation.
     */
    public static String bufferToHex(byte[] buffer) {
        final char hexChars[] = {
                '0', '1', '2', '3', '4', '5', '6', '7', '8',
                '9', 'a', 'b', 'c', 'd', 'e', 'f'
        };

        if (buffer != null) {
            int length = buffer.length;
            if (length > 0) {
                StringBuilder hex = new StringBuilder(2 * length);

                for (int i = 0; i < length; ++i) {
                    byte l = (byte) (buffer[i] & 0x0F);
                    byte h = (byte) ((buffer[i] & 0xF0) >> 4);

                    hex.append(hexChars[h]);
                    hex.append(hexChars[l]);
                }

                return hex.toString().toUpperCase();
            } else {
                return "";
            }
        }

        return null;
    }

    private void handleFileUpload(final String url,
                                  final ArrayList<FileToUpload> filesToUpload,
                                  final ArrayList<NameValue> requestHeaders,
                                  final ArrayList<NameValue> requestParameters)
            throws IOException {

        final String boundary = getBoundary();
        final byte[] boundaryBytes = getBoundaryBytes(boundary);

        HttpURLConnection conn = null;
        OutputStream requestStream = null;

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        try {
            conn = getMultipartHttpURLConnection(url, boundary);

            setRequestHeaders(conn, requestHeaders);

            requestStream = conn.getOutputStream();

            //filesToUpload.size() != 1只传一个文件时不设置boundaryBytes及requestParameters(Form值)
            if (filesToUpload.size() != 1) {
                setRequestParameters(requestStream, requestParameters, boundaryBytes);
            }

            uploadFiles(requestStream, filesToUpload, boundaryBytes);

            if (filesToUpload.size() != 1) {
                final byte[] trailer = getTrailerBytes(boundary);
                requestStream.write(trailer, 0, trailer.length);
            }

            final int serverResponseCode = conn.getResponseCode();
            final String serverResponseMessage = conn.getResponseMessage();

            StringBuilder response = new StringBuilder();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader input = new BufferedReader(new InputStreamReader(conn.getInputStream()), 8192);
                String strLine = null;
                while ((strLine = input.readLine()) != null) {
                    response.append(strLine);
                }
                input.close();
            }

            System.out.println("SERVER RESPONSE BODY " + response);
            for (Map.Entry<String, List<String>> k : conn.getHeaderFields().entrySet()) {
                for (String v : k.getValue()) {
                    System.out.println(k.getKey() + ":" + v);
                }
            }
            broadcastCompleted(serverResponseCode, serverResponseMessage, response.toString());

        } finally {
            closeOutputStream(requestStream);
            closeConnection(conn);
        }
    }

    private String getBoundary() {
        final StringBuilder builder = new StringBuilder();

        builder.append("---------------------------")
                .append(System.currentTimeMillis());

        return builder.toString();
    }

    private byte[] getBoundaryBytes(final String boundary)
            throws UnsupportedEncodingException {
        final StringBuilder builder = new StringBuilder();

        builder.append(NEW_LINE)
                .append(TWO_HYPHENS)
                .append(boundary)
                .append(NEW_LINE);

        return builder.toString().getBytes("US-ASCII");
    }

    private byte[] getTrailerBytes(final String boundary)
            throws UnsupportedEncodingException {
        final StringBuilder builder = new StringBuilder();

        builder.append(NEW_LINE)
                .append(TWO_HYPHENS)
                .append(boundary)
                .append(TWO_HYPHENS)
                .append(NEW_LINE);

        return builder.toString().getBytes("US-ASCII");
    }

    private HttpURLConnection getMultipartHttpURLConnection(final String url,
                                                            final String boundary)
            throws IOException {
        final HttpURLConnection conn;

        if (url.startsWith("https")) {
            AllCertificatesTruster.trustAllSSLCertificates();
            final HttpsURLConnection https = (HttpsURLConnection) new URL(url).openConnection();
            https.setHostnameVerifier(new HostnameVerifier() {

                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }

            });
            conn = https;

        } else {
            conn = (HttpURLConnection) new URL(url).openConnection();
        }

        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setRequestProperty("ENCTYPE", "multipart/form-data");
        conn.setConnectTimeout(30*1000);
        if (boundary == null) {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        }
        conn.setRequestProperty("Content-Type", ContentType.APPLICATION_OCTET_STREAM);

        return conn;
    }

    private void setRequestHeaders(final HttpURLConnection conn,
                                   final ArrayList<NameValue> requestHeaders) {
        if (!requestHeaders.isEmpty()) {
            for (final NameValue param : requestHeaders) {
                conn.setRequestProperty(param.getName(), param.getValue());
            }
        }
    }

    private void setRequestParameters(final OutputStream requestStream,
                                      final ArrayList<NameValue> requestParameters,
                                      final byte[] boundaryBytes)
            throws IOException, UnsupportedEncodingException {
        if (!requestParameters.isEmpty()) {

            for (final NameValue parameter : requestParameters) {
                requestStream.write(boundaryBytes, 0, boundaryBytes.length);
                byte[] formItemBytes = parameter.getBytes();
                requestStream.write(formItemBytes, 0, formItemBytes.length);
            }
        }
        requestStream.write(boundaryBytes, 0, boundaryBytes.length);
    }

    private void uploadFiles(OutputStream requestStream,
                             final ArrayList<FileToUpload> filesToUpload,
                             final byte[] boundaryBytes)
            throws UnsupportedEncodingException,
            IOException,
            FileNotFoundException {

        final long totalBytes = getTotalBytes(filesToUpload);
        long uploadedBytes = 0;

        boolean isAppendMultipartTag = (filesToUpload.size() > 1);

        for (FileToUpload file : filesToUpload) {
            if (isAppendMultipartTag) {
                byte[] headerBytes = file.getMultipartHeader();
                requestStream.write(headerBytes, 0, headerBytes.length);
            }

            final InputStream stream = file.getStream();
            //stream.skip(file.getSizeHasUploaded());
            stream.skip(fileHasUploadSize);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            String fileName = filesToUpload.get(0).getFileName();
            uploadingFileName = fileName;

            StringBuilder eventIdSb = buildEventId(filesToUpload);
            byte[] datas = eventIdSb.toString().getBytes("utf-8");
            requestStream.write(datas);
            //requestStream.flush();

            try {
                while ((bytesRead = stream.read(buffer, 0, buffer.length)) > 0) {
                    if (mFileUploadSuspendList.contains(fileName)) {
                        break;
                    }
                    //requestStream.write(buffer, 0, buffer.length);
                    requestStream.write(buffer, 0, bytesRead);
                    uploadedBytes += bytesRead;
                    broadcastProgress(uploadedBytes, totalBytes);
                }
            } finally {
                closeInputStream(stream);
            }

            if (isAppendMultipartTag) {
                requestStream.write(boundaryBytes, 0, boundaryBytes.length);
            }
        }
    }

    private long getTotalBytes(final ArrayList<FileToUpload> filesToUpload) {
        long total = 0;

        for (FileToUpload file : filesToUpload) {
            total += file.length();
        }

        return total;
    }

    private void closeInputStream(final InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void closeOutputStream(final OutputStream stream) {
        if (stream != null) {
            try {
                stream.flush();
                stream.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void closeConnection(final HttpURLConnection connection) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void broadcastProgress(final long uploadedBytes, final long totalBytes) {

        final int progress = (int) (uploadedBytes * 100 / totalBytes);
        if (progress <= lastPublishedProgress) return;
        lastPublishedProgress = progress;

        updateNotificationProgress(progress);

        final Intent intent = new Intent(BROADCAST_ACTION);
        intent.putExtra(STATUS, STATUS_IN_PROGRESS);
        intent.putExtra(PROGRESS, progress);
        if(uploadingFileName != null && !uploadingFileName.equals("")) {
            intent.putExtra(UPLOADING_FILE, uploadingFileName);
        }
        sendBroadcast(intent);
    }

    private void broadcastCompleted(final int responseCode, final String responseMessage, final String responseBody) {

        final String filteredMessage;
        if (responseMessage == null) {
            filteredMessage = "";
        } else {
            filteredMessage = responseMessage;
        }

        updateNotificationCompleted();

        final Intent intent = new Intent(BROADCAST_ACTION);
        intent.putExtra(STATUS, STATUS_COMPLETED);
        intent.putExtra(SERVER_RESPONSE_CODE, responseCode);
        intent.putExtra(SERVER_RESPONSE_MESSAGE, filteredMessage);
        intent.putExtra(SERVER_RESPONSE_BODY, responseBody);
        if(uploadingFileName != null && !uploadingFileName.equals("")) {
            intent.putExtra(UPLOADING_FILE, uploadingFileName);
        }
        sendBroadcast(intent);

        Log.i("upload-----:", "upload:" + fileCount);
    }

    private void broadcastError(final Exception exception) {

        updateNotificationError();

        final Intent intent = new Intent(BROADCAST_ACTION);
        intent.setAction(BROADCAST_ACTION);
        intent.putExtra(STATUS, STATUS_ERROR);
        intent.putExtra(ERROR_EXCEPTION, exception);
        if(uploadingFileName != null && !uploadingFileName.equals("")) {
            intent.putExtra(UPLOADING_FILE, uploadingFileName);
        }
        sendBroadcast(intent);
    }

    private void createNotification() {
        notification.setContentTitle(notificationConfig.getTitle())
                .setContentText(notificationConfig.getMessage())
                .setSmallIcon(notificationConfig.getIconResourceID())
                .setProgress(100, 0, true);
        notificationManager.notify(0, notification.build());
    }

    private void updateNotificationProgress(final int progress) {
        notification.setContentTitle(notificationConfig.getTitle())
                .setContentText(notificationConfig.getMessage())
                .setSmallIcon(notificationConfig.getIconResourceID())
                .setProgress(100, progress, false);
        notificationManager.notify(0, notification.build());
    }

    private void updateNotificationCompleted() {
        if (notificationConfig.isAutoClearOnSuccess()) {
            notificationManager.cancel(0);
            return;
        } else {
            notification.setContentTitle(notificationConfig.getTitle())
                    .setContentText(notificationConfig.getCompleted())
                    .setSmallIcon(notificationConfig.getIconResourceID())
                    .setProgress(0, 0, false);
            notificationManager.notify(0, notification.build());
        }
    }

    private void updateNotificationError() {
        notification.setContentTitle(notificationConfig.getTitle())
                .setContentText(notificationConfig.getError())
                .setSmallIcon(notificationConfig.getIconResourceID())
                .setProgress(0, 0, false);
        notificationManager.notify(0, notification.build());
    }

    private StringBuilder buildEventId(final ArrayList<FileToUpload> filesToUpload) {

        String fileName = filesToUpload.get(0).getFileName();
        fileName = fileName.substring(0, fileName.indexOf("."));
        StringBuilder eventIdSb = new StringBuilder(fileName);
        int paddingCount = 64 - eventIdSb.length();
        for (int i = 0; i < paddingCount; i++) {
            eventIdSb.insert(0, "0");
        }

        return eventIdSb;
    }
}
