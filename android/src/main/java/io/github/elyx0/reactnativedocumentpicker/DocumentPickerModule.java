package io.github.elyx0.reactnativedocumentpicker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

/**
 * @see <a href="https://developer.android.com/guide/topics/providers/document-provider.html">android documentation</a>
 */
public class DocumentPickerModule extends ReactContextBaseJavaModule {
	private static final String NAME = "RNDocumentPicker";
	private static final int READ_REQUEST_CODE = 41;

	private static final String E_ACTIVITY_DOES_NOT_EXIST = "ACTIVITY_DOES_NOT_EXIST";
	private static final String E_FAILED_TO_SHOW_PICKER = "FAILED_TO_SHOW_PICKER";
	private static final String E_DOCUMENT_PICKER_CANCELED = "DOCUMENT_PICKER_CANCELED";
	private static final String E_UNABLE_TO_OPEN_FILE_TYPE = "UNABLE_TO_OPEN_FILE_TYPE";
	private static final String E_UNKNOWN_ACTIVITY_RESULT = "UNKNOWN_ACTIVITY_RESULT";
	private static final String E_INVALID_DATA_RETURNED = "INVALID_DATA_RETURNED";
	private static final String E_UNEXPECTED_EXCEPTION = "UNEXPECTED_EXCEPTION";

	private static final String OPTION_TYPE = "type";
	private static final String OPTION_MULTIPLE = "multiple";
	private static final String OPTION_PRIVATE = "private";

	private static final String FIELD_URI = "uri";
	private static final String FIELD_NAME = "name";
	private static final String FIELD_TYPE = "type";
	private static final String FIELD_SIZE = "size";

	private final ActivityEventListener activityEventListener = new BaseActivityEventListener() {
		@Override
		public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
			if (requestCode == READ_REQUEST_CODE) {
				if (promise != null) {
					onShowActivityResult(resultCode, data, promise);
					promise = null;
				}
			}
		}
	};

	private String[] readableArrayToStringArray(ReadableArray readableArray) {
		int l = readableArray.size();
		String[] array = new String[l];
		for (int i = 0; i < l; ++i) {
			array[i] = readableArray.getString(i);
		}
		return array;
	}

	private Promise promise;
	private Uri outputFileUri;

	public DocumentPickerModule(ReactApplicationContext reactContext) {
		super(reactContext);
		reactContext.addActivityEventListener(activityEventListener);
	}

	@Override
	public void onCatalystInstanceDestroy() {
		super.onCatalystInstanceDestroy();
		getReactApplicationContext().removeActivityEventListener(activityEventListener);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@ReactMethod
	public void pick(ReadableMap args, Promise promise) {
		Activity currentActivity = getCurrentActivity();

		if (currentActivity == null) {
			promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Current activity does not exist");
			return;
		}

		this.promise = promise;

		try {
			// Create document selection intent
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);

			intent.setType("*/*");
			if (!args.isNull(OPTION_TYPE)) {
				ReadableArray types = args.getArray(OPTION_TYPE);
				if (types.size() > 1) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
						String[] mimeTypes = readableArrayToStringArray(types);
						intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
					} else {
						Log.e(NAME, "Multiple type values not supported below API level 19");
					}
				} else if (types.size() == 1) {
					intent.setType(types.getString(0));
				}
			}

			boolean multiple = !args.isNull(OPTION_MULTIPLE) && args.getBoolean(OPTION_MULTIPLE);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
				intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
			}

			List<Intent> allIntents = getAllIntentsForIntent(intent, null);

			outputFileUri = null;
			// the main intent is the last in the list (fucking android) so pickup the useless one
			Intent mainIntent = allIntents.get(allIntents.size() - 1);
			for (Intent intent2 : allIntents) {
				ComponentName componentName = intent.getComponent();
				if (componentName != null
				&& componentName.getClassName().equals("com.android.documentsui.DocumentsActivity")) {
					mainIntent = intent2;
					break;
				}
			}
			allIntents.remove(mainIntent);

			// Add intents to capture media instead of pick files from filesystem
			PackageManager packageManager = currentActivity.getPackageManager();

			if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
				// collect all image capture intents
				if (hasMimeType(args, "image")) {
					boolean isPrivate = !args.isNull(OPTION_PRIVATE) && args.getBoolean(OPTION_PRIVATE);
					outputFileUri = Uri.fromFile(createImageFile(isPrivate));

					allIntents.addAll(getAllIntentsForIntent(
						new Intent(MediaStore.ACTION_IMAGE_CAPTURE), outputFileUri));
				}

				// // Add video capture intent
				// if (hasMimeType(args, "video"))
				//   allIntents.addAll(getAllIntentsForIntent(
				//     new Intent(MediaStore.ACTION_VIDEO_CAPTURE), null));
			}

			// if (packageManager.hasSystemFeature(PackageManager.FEATURE_RECORD_AUDIO)) {
			//  // Add audio capture intent
			// 	if (hasMimeType(args, "audio"))
			//   allIntents.addAll(getAllIntentsForIntent(
			//     new Intent(MediaStore.ACTION_AUDIO_CAPTURE), null));
			// }

			// if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
				mainIntent = Intent.createChooser(mainIntent, null);
			// }

			mainIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, allIntents.toArray(new Parcelable[allIntents.size()]));

			// Show picker
			currentActivity.startActivityForResult(mainIntent, READ_REQUEST_CODE, Bundle.EMPTY);
		} catch (Exception e) {
			e.printStackTrace();
			this.promise.reject(E_FAILED_TO_SHOW_PICKER, e.getLocalizedMessage());
			this.promise = null;
		}
	}

	public void onShowActivityResult(int resultCode, Intent data, Promise promise) {
		if (resultCode == Activity.RESULT_CANCELED) {
			promise.reject(E_DOCUMENT_PICKER_CANCELED, "User canceled document picker");
		} else if (resultCode == Activity.RESULT_OK) {
			Uri uri = null;
			ClipData clipData = null;

			if (data != null) {
				uri = data.getData();
				clipData = data.getClipData();
			}

			boolean isCamera = false;
			// If intent came from camera
			if (uri == null && data == null) {
				uri = outputFileUri;
				isCamera = true;
			}

			try {
				WritableArray results = Arguments.createArray();

				if (uri != null && isCamera) {
					results.pushMap(getMetadata(uri));
				} else if (clipData != null && clipData.getItemCount() > 0) {
					final int length = clipData.getItemCount();
					for (int i = 0; i < length; ++i) {
						ClipData.Item item = clipData.getItemAt(i);
						results.pushMap(getMetadata(item.getUri()));
					}
				} else {
					promise.reject(E_INVALID_DATA_RETURNED, "Invalid data returned by intent");
					return;
				}

				promise.resolve(results);
			} catch (Exception e) {
				promise.reject(E_UNEXPECTED_EXCEPTION, e.getLocalizedMessage(), e);
			}
		} else {
			promise.reject(E_UNKNOWN_ACTIVITY_RESULT, "Unknown activity result: " + resultCode);
		}
	}

	private WritableMap getMetadata(Uri uri) {
		WritableMap map = Arguments.createMap();

		map.putString(FIELD_URI, uri.toString());

		ContentResolver contentResolver = getReactApplicationContext().getContentResolver();

		map.putString(FIELD_NAME, uri.getLastPathSegment());
		map.putString(FIELD_TYPE, contentResolver.getType(uri));

		Cursor cursor = contentResolver.query(uri, null, null, null, null, null);

		try {
			if (cursor != null && cursor.moveToFirst()) {
				int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (!cursor.isNull(displayNameIndex)) {
					map.putString(FIELD_NAME, cursor.getString(displayNameIndex));
				}

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
					int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
					if (!cursor.isNull(mimeIndex)) {
						map.putString(FIELD_TYPE, cursor.getString(mimeIndex));
					}
				}

				int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
				if (!cursor.isNull(sizeIndex)) {
					map.putInt(FIELD_SIZE, cursor.getInt(sizeIndex));
				}
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}

		return map;
	}

	private File createImageFile(boolean isPrivate) throws IOException {
		// Create an image file name
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

		File storageDir = getCurrentActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES);

		if (!isPrivate) {
			storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

			// Public dirs need to be created by hand if they don't exits, only
			// private ones are created automatically by Android APIs.
			storageDir.mkdirs();
		}

		File image = File.createTempFile(
				timeStamp,  /* prefix */
				".png",     /* suffix */
				storageDir  /* directory */
		);

		return image;
	}

	private boolean hasMimeType(ReadableMap args, String type) {
		if (args.isNull(OPTION_TYPE)) return true;

		ReadableArray types = args.getArray(OPTION_TYPE);
		int l = types.size();

		if (l == 0) return true;

		for (int i = 0; i < l; ++i) {
			String mime = types.getString(i);

			if (mime.equals("*/*") || mime.split("/")[0].equals(type)) return true;
		}

		return false;
	}

	/**
	 * Get intents for all the activities that can process a specified one
	 *
	 * For example, if the intent is related to image capture, return a list of
	 * intents to call to all the activities (applications) that can do image
	 * capturing instead of the user defined default, usually the `Camera` app.
	 */
	private List<Intent> getAllIntentsForIntent(Intent intent, Uri outputFileUri) {
		List<Intent> result = new ArrayList<>();

		List<ResolveInfo> activities = getCurrentActivity().getPackageManager()
			.queryIntentActivities(intent, 0);
		for (ResolveInfo res : activities) {
				Intent intent2 = new Intent(intent);
				intent2.setComponent(new ComponentName(res.activityInfo.packageName,
																							 res.activityInfo.name));
				intent2.setPackage(res.activityInfo.packageName);

				if (outputFileUri != null)
					intent2.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri);

				result.add(intent2);
		}

		return result;
	}
}
