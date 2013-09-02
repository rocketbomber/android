package com.twofours.surespot.voice;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.AsyncTask;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.chat.SurespotMessage;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.encryption.EncryptionController;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.NetworkController;

public class VoiceMessageContainer {
	private SeekBar mSeekBar;
	private MediaPlayer mPlayer;
	private SurespotMessage mMessage;
	private static final String TAG = "VoiceMessageContainer";
	private NetworkController mNetworkController;
	private File mAudioFile;
	private SeekBarThread mSeekBarThread;

	public VoiceMessageContainer(NetworkController networkController, SurespotMessage message) {
		mMessage = message;
		mNetworkController = networkController;

	}

	private void prepareAudio(final IAsyncCallback<Boolean> callback) {

		try {
			// create temp file to save un-encrypted audio data to (MediaPlayer can't stream from uh a Stream (InputStream) for some ass backwards reason).
			mAudioFile = File.createTempFile("sound", ".m4a");
		}
		catch (IOException e) {
			// TODO tell user
			SurespotLog.w(TAG, e, "playVoiceMessage");
			callback.handleResponse(false);
			return;
		}
		// download and decrypt
		new AsyncTask<Void, Void, Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				byte[] soundbytes = mMessage.getPlainBinaryData();
				// TODO - progress

				if (soundbytes == null) {
					// see if the data has been sent to us inline
					InputStream voiceStream = null;
					if (mMessage.getInlineData() == null) {
						SurespotLog.v(TAG, "getting voice stream from cloud");
						voiceStream = mNetworkController.getFileStream(MainActivity.getContext(), mMessage.getData());

					}
					else {
						SurespotLog.v(TAG, "getting voice stream from inlineData");
						voiceStream = new ByteArrayInputStream(mMessage.getInlineData());

					}

					if (voiceStream != null) {

						PipedOutputStream out = new PipedOutputStream();
						PipedInputStream inputStream;
						try {
							inputStream = new PipedInputStream(out);

							EncryptionController.runDecryptTask(mMessage.getOurVersion(), mMessage.getOtherUser(), mMessage.getTheirVersion(),
									mMessage.getIv(), voiceStream, out);

							soundbytes = Utils.inputStreamToBytes(inputStream);

						}
						catch (InterruptedIOException ioe) {

							SurespotLog.w(TAG, ioe, "playVoiceMessage");

						}
						catch (IOException e) {
							SurespotLog.w(TAG, e, "playVoiceMessage");
						}
					}
				}
				else {
					SurespotLog.v(TAG, "getting voice stream from cache");
				}
				if (soundbytes != null) {
					FileOutputStream fos;
					try {
						fos = new FileOutputStream(mAudioFile);
						fos.write(soundbytes);
						fos.close();

						mMessage.setPlainBinaryData(soundbytes);

						return true;

					}
					catch (IOException e) {
						SurespotLog.w(TAG, e, "playVoiceMessage");
					}
				}

				return false;
			}

			@Override
			protected void onPostExecute(Boolean result) {
				callback.handleResponse(result);
				// if (result) {
				// // fuck me it worked lets play it
				// startPlaying(seekBar, tempFile.getAbsolutePath(), true);
				// }
			}
		}.execute();

	}

	private void prepAndPlay(int progress) {
		String path = mAudioFile.getAbsolutePath();
		try {
			if (mPlayer == null) {

				mSeekBarThread = new SeekBarThread();
				mPlayer = new MediaPlayer();
				mPlayer.setDataSource(path);
				mPlayer.prepare();
			}
			else {
				// mPlayer.reset();
			}

			if (progress > 0) {
				mPlayer.seekTo(progress);
			}
			
			new Thread(mSeekBarThread).start();
			mPlayer.start();

			mPlayer.setOnCompletionListener(new OnCompletionListener() {

				@Override
				public void onCompletion(MediaPlayer mp) {

					// playCompleted();
				}
			});

		}
		catch (IOException e) {
			SurespotLog.e(TAG, e, "preparePlayer failed");
		}

	}

	public void attachSeekBar(SeekBar seekBar) {
		this.mSeekBar = seekBar;

	}

	public void play(final int progress) {
		prepareAudio(new IAsyncCallback<Boolean>() {

			@Override
			public void handleResponse(Boolean result) {
				if (result) {
					prepAndPlay(progress);
				}

			}
		});

	}

	private void playCompleted() {
		mSeekBarThread.completed();
	}

	private class SeekBarThread implements Runnable {
		private boolean mRun = true;

		@Override
		public void run() {
			try {
				mRun = true;
				mSeekBar.setMax(100);
				mSeekBar.setOnSeekBarChangeListener(new MyOnSeekBarChangedListener());
				while (mRun) {

					int currentPosition = mPlayer.getCurrentPosition();
					int duration = mPlayer.getDuration();
					int progress = (int) (((float) currentPosition / (float) duration) * 101);
					SurespotLog.v(TAG, "SeekBarThread: %s, currentPosition: %d, duration: %d, percent: %d", this, currentPosition, duration, progress);
					if (progress < 0)
						progress = 0;
					if (progress > 90)
						progress = 100;

					if (currentPosition < duration) {
						mSeekBar.setProgress(progress);

					}
					else {
						mSeekBar.setProgress(0);
					}
					try {
						Thread.sleep(100);
					}
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					if (progress == 100) {
						completed();
					}
				}
			}
			catch (Exception e) {
				SurespotLog.w(TAG, e, "SeekBarThread");
			}
		}

		public void completed() {
			SurespotLog.v(TAG, "SeekBarThread completed");
			mRun = false;
			mSeekBar.setProgress(100);
		}
	}

	private class MyOnSeekBarChangedListener implements OnSeekBarChangeListener {

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
			if (fromTouch) {
				SurespotLog.v(TAG, "onProgressChanged, progress: %d", progress);
				int time = (int) (mPlayer.getDuration() * (float) progress / 101);
				// if (mVoiceController.)

				if (mPlayer.isPlaying()) {
					SurespotLog.v(TAG, "onProgressChanged,  seekingTo: %d", progress);
					mPlayer.seekTo(time);
					
				}
				else {
					SurespotLog.v(TAG, "onProgressChanged,  playing from: %d", progress);
					play(time);

				}
			}
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			// Empty method
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			// Empty method
		}
	}
};