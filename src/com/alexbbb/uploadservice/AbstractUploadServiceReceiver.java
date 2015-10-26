package com.alexbbb.uploadservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Abstract broadcast receiver from which to inherit when creating a receiver
 * for {@link UploadService}.
 *
 * It provides the boilerplate code to properly handle broadcast messages coming
 * from the upload service and dispatch them to the proper handler method.
 *
 * @author alexbbb (Alex Gotev)
 *
 */
public abstract class AbstractUploadServiceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent != null) {
            if (UploadService.BROADCAST_ACTION.equals(intent.getAction())) {
                final int status = intent.getIntExtra(UploadService.STATUS, 0);

                switch (status) {
                    case UploadService.STATUS_ERROR:
                        final Exception exception = (Exception) intent.getSerializableExtra(UploadService.ERROR_EXCEPTION);
                        final String fileNameException = (String)intent.getStringExtra(UploadService.UPLOADING_FILE);
                        onError(exception, fileNameException);
                        break;

                    case UploadService.STATUS_COMPLETED:
                        final int responseCode = intent.getIntExtra(UploadService.SERVER_RESPONSE_CODE, 0);
                        final String responseMsg = intent.getStringExtra(UploadService.SERVER_RESPONSE_MESSAGE);
                        final String responseBody = intent.getStringExtra(UploadService.SERVER_RESPONSE_BODY);
                        final String fileNameComplete = (String)intent.getStringExtra(UploadService.UPLOADING_FILE);
                        onCompleted(responseCode, responseMsg, responseBody, fileNameComplete);
                        
                        break;

                    case UploadService.STATUS_IN_PROGRESS:
                        final int progress = intent.getIntExtra(UploadService.PROGRESS, 0);
                        final String fileNameInProcess = (String)intent.getStringExtra(UploadService.UPLOADING_FILE);
                        onProgress(progress, fileNameInProcess);
                        break;

                    default:
                        break;
                }
            }
        }

    }

    /**
     * Called when the upload progress changes.
     * @param progress value from 0 to 100
     */
    public abstract void onProgress(final int progress, final String fileName);

    /**
     * Called when an error happens during the upload.
     * @param exception exception that caused the error
     */
    public abstract void onError(final Exception exception, final String fileName);

    /**
     * Called when the upload is completed successfully.
     * @param serverResponseCode status code returned by the server
     * @param serverResponseMessage string containing the response received from the server
     */
    public abstract void onCompleted(final int serverResponseCode, final String serverResponseMessage, final String serverResponseBody, final String fileName);
}
