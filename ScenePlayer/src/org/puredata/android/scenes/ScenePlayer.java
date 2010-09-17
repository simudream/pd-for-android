/**
 * 
 * @author Peter Brinkmann (peter.brinkmann@gmail.com)
 * 
 * For information on usage and redistribution, and for a DISCLAIMER OF ALL
 * WARRANTIES, see the file, "LICENSE.txt," in this distribution.
 * 
 */

package org.puredata.android.scenes;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.puredata.android.service.PdService;
import org.puredata.core.PdBase;
import org.puredata.core.utils.PdDispatcher;
import org.puredata.core.utils.PdListener;
import org.puredata.core.utils.PdUtils;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;


public class ScenePlayer extends Activity implements SensorEventListener, OnTouchListener, OnClickListener {

	public static final String SCENE = "SCENE";
	private static final String TAG = "Pd Scene Player";
	private static final String RJ_IMAGE_ANDROID = "rj_image_android";
	private static final String RJ_TEXT_ANDROID = "rj_text_android";
	private static final String TRANSPORT = "#transport";
	private static final String ACCELERATE = "#accelerate";
	private volatile ProgressDialog progress = null;
	private SceneView sceneView;
	private TextView logs;
	private ToggleButton play;
	private ToggleButton record;
	private Button info;
	private File sceneFolder;
	private PdService pdService = null;
	private String patch = null;
	private final File recDir = new File("/sdcard/pd");
	private final Map<String, String> sceneInfo = new HashMap<String, String>();
	private final PdDispatcher dispatcher = new PdDispatcher() {
		@Override
		public void print(String s) {
			post(s);
		}
	};

	private void post(final String msg) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				logs.append(msg + ((msg.endsWith("\n")) ? "" : "\n"));
			}
		});
	}

	private final PdListener overlayListener = new PdListener.Adapter() {

		private final Map<String, Overlay> overlays = new HashMap<String, Overlay>();

		@Override
		public synchronized void receiveList(Object... args) {
			String key = (String) args[0];
			String cmd = (String) args[1];
			if (overlays.containsKey(key)) {
				Overlay overlay = overlays.get(key);
				if (cmd.equals("visible")) {
					boolean flag = ((Float) args[2]).floatValue() > 0.5f;
					overlay.setVisible(flag);
				} else if (cmd.equals("move")) {
					float x = ((Float) args[2]).floatValue();
					float y = ((Float) args[3]).floatValue();
					overlay.setPosition(x, y);
				} else {
					if (overlay instanceof TextOverlay) {
						TextOverlay textOverlay = (TextOverlay) overlay;
						if (cmd.equals("text")) {
							textOverlay.setText((String) args[2]);
						} else if (cmd.equals("size")) {
							textOverlay.setSize(((Float) args[2]).floatValue());
						}
					} else {
						ImageOverlay imgOverlay = (ImageOverlay) overlay;
						float val = ((Float) args[2]).floatValue();
						if (cmd.equals("ref")) {
							boolean flag = val > 0.5f;
							imgOverlay.setCentered(flag);
						} else if (cmd.equals("scale")) {
							float sy = ((Float) args[3]).floatValue();
							imgOverlay.setScale(val, sy);
						} else if (cmd.equals("rotate")) {
							imgOverlay.setAngle(val);
						} else if (cmd.equals("alpha")) {
							imgOverlay.setAlpha(val);
						}
					}
				}
			} else {
				String arg = (String) args[2];
				Overlay overlay;
				if (cmd.equals("load")) {
					overlay = new ImageOverlay(new File(sceneFolder, arg).getAbsolutePath());
				} else if (cmd.equals("text")) {
					overlay = new TextOverlay(arg);
				} else return;
				sceneView.addOverlay(overlay);
				overlays.put(key, overlay);
			}
		}
	};

	private final ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			pdService = ((PdService.PdBinder)service).getService();
			initPd();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			// this method will never be called
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String path = intent.getStringExtra(SCENE);
		if (path != null) {
			SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
			sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME);
			sceneFolder = new File(path);
			initGui();
			progress = new ProgressDialog(this);
			progress.setCancelable(false);
			progress.setIndeterminate(true);
			progress.setMessage("Loading scene...");
			progress.show();
			new Thread() {
				@Override
				public void run() {
					fixScene();
					bindService(new Intent(ScenePlayer.this, PdService.class), serviceConnection, BIND_AUTO_CREATE);
				}
			}.start();
		} else {
			Log.e(TAG, "launch intent without scene path");
			finish();
		}
	}

	private void fixScene() {
		// weird little hack to avoid having our rj_image.pd and such masked by files in the scene
		for (String s: new String[] {"rj_image", "rj_text", "soundinput", "soundoutput"}) {
			new File(sceneFolder, s + ".pd").delete();
			new File(sceneFolder, "rj/" + s + ".pd").delete();
		}
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		final float q = 1.0f / SensorManager.GRAVITY_EARTH;
		PdBase.sendList(ACCELERATE, -event.values[0] * q, -event.values[1] * q, -event.values[2] * q);
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// don't care
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		return (v == sceneView) && VersionedTouch.evaluateTouch(event, sceneView.getWidth(), sceneView.getHeight());
	}

	@Override
	protected void onPause() {
		dismissProgressDialog();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		cleanup();
	}

	private void initGui() {
		setContentView(R.layout.main);
		TextView tv = (TextView) findViewById(R.id.scene_title);
		tv.setText(sceneFolder.getName());
		sceneView = (SceneView) findViewById(R.id.scene_pic);
		sceneView.setOnTouchListener(this);
		sceneView.setImageBitmap(BitmapFactory.decodeFile(new File(sceneFolder, "image.jpg").getAbsolutePath()));
		play = (ToggleButton) findViewById(R.id.scene_pause);
		play.setOnClickListener(this);
		record = (ToggleButton) findViewById(R.id.scene_record);
		record.setOnClickListener(this);
		info = (Button) findViewById(R.id.scene_info);
		info.setOnClickListener(this);
		logs = (TextView) findViewById(R.id.scene_logs);
		logs.setMovementMethod(new ScrollingMovementMethod());
	}

	private void initPd() {
		try {
			PdBase.setReceiver(dispatcher);
			dispatcher.addListener(RJ_IMAGE_ANDROID, overlayListener);
			dispatcher.addListener(RJ_TEXT_ANDROID, overlayListener);
			startAudio();
		} catch (IOException e) {
			post(e.toString() + "; exiting now");
			finish();
		}
		dismissProgressDialog();
	}

	private void dismissProgressDialog() {
		if (progress != null) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					progress.dismiss();
					progress = null;
				}
			});
		}
	}

	@Override
	public void finish() {
		cleanup();
		super.finish();
	}

	private void cleanup() {
		// make sure to release all resources
		stopRecording();
		stopAudio();
		if (patch != null) {
			PdUtils.closePatch(patch);
			patch = null;
		}
		dispatcher.release();
		PdBase.release();
		try {
			unbindService(serviceConnection);
		} catch (IllegalArgumentException e) {
			// already unbound
			pdService = null;
		}
	}

	@Override
	public void onClick(View v) {
		if (pdService == null) return;
		if (v.equals(play)) {
			if (play.isChecked()) {
				try {
					startAudio();
				} catch (IOException e) {
					post(e.toString());
				}
			} else {
				stopAudio();
			}
		} else if (v.equals(record)) {
			if (record.isChecked()) {
				startRecording();
			} else {
				stopRecording();
			}
		} else if (v.equals(info)) {
			showInfo();
		}
	}

	private void startRecording() {
		String name = sceneFolder.getName();
		String filename = new File(recDir, name.substring(0, name.length()-3) + "-" + System.currentTimeMillis() + ".wav").getAbsolutePath();
		PdBase.sendMessage(TRANSPORT, "scene", filename);
		PdBase.sendMessage(TRANSPORT, "record", 1);
		post("recording to " + filename);
	}

	private void stopRecording() {
		PdBase.sendMessage(TRANSPORT, "record", 0);
		post("finished recording");
	}

	private void startAudio() throws IOException {
		String name = getResources().getString(R.string.app_name);
		pdService.initAudio(22050, 1, 2, -1);   // negative values default to PdService preferences
		if (patch == null) {
			patch = PdUtils.openPatch(new File(sceneFolder, "_main.pd"));
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// do nothing
			}
		}
		pdService.startAudio(new Intent(this, ScenePlayer.class), android.R.drawable.ic_media_play, name, "Return to " + name + ".");
		PdBase.sendMessage(TRANSPORT, "play", 1);
	}

	private void stopAudio() {
		PdBase.sendMessage(TRANSPORT, "play", 0);
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			// do nothing
		}
		pdService.stopAudio();
	}

	private void showInfo() {
		readInfo();
		AlertDialog.Builder ad = new AlertDialog.Builder(this);
		if (sceneInfo.isEmpty()) {
			ad.setTitle("Oops");
			ad.setMessage("Info not available...");
		} else {
			ad.setTitle("" + sceneInfo.get("name") + "\n" + sceneInfo.get("author"));
			ad.setMessage("" + sceneInfo.get("description") + "\n\nCategory: " + sceneInfo.get("category"));
		}
		ad.setNeutralButton(android.R.string.ok, null);
		ad.setCancelable(true);
		ad.show();
	}

	private void readInfo() {
		if (!sceneInfo.isEmpty()) return;
		try {
			Document dbf = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(sceneFolder, "Info.plist"));
			Element infoEntries = (Element) dbf.getElementsByTagName("dict").item(1);
			if (infoEntries == null) throw new SAXException("Not enough dict nodes in Info.plist");
			NodeList keys = infoEntries.getElementsByTagName("key");
			NodeList values = infoEntries.getElementsByTagName("string");
			if (keys.getLength() != values.getLength()) throw new SAXException("Mismatched number of keys and values");
			for (int i = 0; i < keys.getLength(); i++) {
				String key = ((CharacterData) keys.item(i).getLastChild()).getData();
				String value = ((CharacterData) values.item(i).getLastChild()).getData();
				sceneInfo.put(key, value);
			}
		} catch (Exception e) {
			Log.e(TAG, e.toString());
			sceneInfo.clear();
		}
	}
}
