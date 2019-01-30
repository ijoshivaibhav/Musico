package example.vaibhav.com.musico.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

import example.vaibhav.com.musico.Audio;
import example.vaibhav.com.musico.MainActivity;
import example.vaibhav.com.musico.PlaybackStatus;
import example.vaibhav.com.musico.R;
import example.vaibhav.com.musico.StorageUtil;

public class MediaPlayerService extends Service implements MediaPlayer.OnCompletionListener
        , MediaPlayer.OnPreparedListener
        , MediaPlayer.OnErrorListener
        , MediaPlayer.OnSeekCompleteListener
        , MediaPlayer.OnInfoListener
        , MediaPlayer.OnBufferingUpdateListener
        , AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY = "example.vaibhav.com.musico.ACTION_PLAY";
    public static final String ACTION_PAUSE = "example.vaibhav.com.musico.ACTION_PAUSE";
    public static final String ACTION_PREVIOUS = "example.vaibhav.com.musico.ACTION_PREVIOUS";
    public static final String ACTION_NEXT = "example.vaibhav.com.musico.ACTION_NEXT";
    public static final String ACTION_STOP = "example.vaibhav.com.musico.ACTION_STOP";
    private static final int NOTIFICATION_ID = 101;
    private final IBinder iBinder = new LocalBinder();
    private MediaSessionManager mediaSessionManager;
    private MediaSessionCompat mediaSessionCompat;
    private MediaControllerCompat.TransportControls transportControls;
    private MediaPlayer mediaPlayer;
    private String mediaFile;
    private int resumePosition;
    private AudioManager audioManager;
    private boolean ongoingCall = false;
    private PhoneStateListener phoneStateListener;
    private TelephonyManager telephonyManager;
    //List of available Audio files
    private ArrayList<Audio> audioList;
    private int audioIndex = -1;
    private Audio activeAudio; //an object of the currently playing audio
    private BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            pauseMedia();
            buildNotification(PlaybackStatus.PAUSED);
        }
    };

    private BroadcastReceiver playNewAudio = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            audioIndex = new StorageUtil(getApplicationContext()).loadAudioIndex();

            if (audioIndex != -1 && audioIndex < audioList.size()) {
                activeAudio = audioList.get(audioIndex);

            } else {
                stopSelf();
            }
            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new Audio
            stopMedia();
            mediaPlayer.reset();
            initMediaPlayer();
            updateMetaData();
            buildNotification(PlaybackStatus.PLAYING);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {

        return iBinder;
    }

    private void initMediaPlayer() {

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnBufferingUpdateListener(this);
        mediaPlayer.setOnSeekCompleteListener(this);
        mediaPlayer.setOnInfoListener(this);
        //Reset so that the MediaPlayer is not pointing to another data source
        mediaPlayer.reset();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        try {
            mediaPlayer.setDataSource(activeAudio.getData());
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
        }

        mediaPlayer.prepareAsync();


    }

    private void playMedia() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    public void stopMedia() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying())
            mediaPlayer.stop();
    }

    private void pauseMedia() {
        if (mediaPlayer.isPlaying())
            mediaPlayer.pause();
        resumePosition = mediaPlayer.getCurrentPosition();

    }

    private void resumeMedia() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.seekTo(resumePosition);
            mediaPlayer.start();
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        stopMedia();
        stopSelf();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        //Invoked when the media source is ready for playback.
        playMedia();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        //Invoked when there has been an error during an asynchronous operation


        switch (what) {
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                Log.d("MediaPlayer Error", "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED " + extra);
                break;

            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN " + extra);
                break;
        }
        return false;
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {

    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {

            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                if (mediaPlayer == null) initMediaPlayer();
                else if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                mediaPlayer.setVolume(1.0f, 1.0f);
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mediaPlayer.isPlaying()) mediaPlayer.setVolume(0.1f, 0.1f);
                break;

        }

    }

    private boolean requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            //Focus granted
            return true;
        }
        //Could not gain focus
        return false;
    }

    //The system calls this method when an activity, requests the service be started

    private boolean removeAudioFocus() {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                audioManager.abandonAudioFocus(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            //Load data from SharedPreferences
            StorageUtil storage = new StorageUtil(getApplicationContext());
            audioList = storage.loadAudio();
            audioIndex = storage.loadAudioIndex();

            if (audioIndex != -1 && audioIndex < audioList.size()) {
                //index is in a valid range
                activeAudio = audioList.get(audioIndex);
            } else {
                stopSelf();
            }
        } catch (NullPointerException e) {
            stopSelf();
        }

        //Request audio focus
        if (requestAudioFocus() == false) {
            //Could not gain focus
            stopSelf();
        }

        if (mediaSessionManager == null) {
            try {
                initMediaSession();
                initMediaPlayer();
            } catch (RemoteException e) {
                e.printStackTrace();
                stopSelf();
            }
            buildNotification(PlaybackStatus.PLAYING);
        }

        //Handle Intent action from MediaSession.TransportControls
        handleIncomingActions(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            stopMedia();
            mediaPlayer.release();
        }
        removeAudioFocus();
        // disable the phone state listener
        if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }

        removeNotification();

        //unregister receivers
        unregisterReceiver(becomingNoisyReceiver);
        unregisterReceiver(playNewAudio);

        ///clear cached playlist
        new StorageUtil(getApplicationContext()).clearCachedAudioPlaylist();
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {

    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        return false;
    }

    private void registerBecomingNoisyReceiver() {
        //register after getting audio focus
        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(becomingNoisyReceiver, intentFilter);
    }

    private void callStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        //Starting listening for PhoneState changes
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {

                switch (state) {
                    //if at least one call exists or the phone is ringing
                    //pause the MediaPlayer
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                    case TelephonyManager.CALL_STATE_RINGING:
                        if (mediaPlayer != null) {
                            pauseMedia();
                            ongoingCall = true;
                        }
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        if (mediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false;
                                resumeMedia();
                            }

                        }
                        break;

                }
            }
        };

        // Register the listener with the telephony manager
        // Listen for changes to the device call state.
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //Perform one-time setup procedures

        // Manage incoming phone calls during playback.
        // Pause MediaPlayer on incoming call,
        // Resume on hangup.
        callStateListener();
        //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver();
        //Listen for new Audio to play -- BroadcastReceiver
        register_playNewAudio();
    }

    private void initMediaSession() throws RemoteException {
        if (mediaSessionManager != null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        // create new media session
        mediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "AudioPlayer");

        //get media session transport control
        transportControls = mediaSessionCompat.getController().getTransportControls();
        // set Media session -> ready to receive media commands
        mediaSessionCompat.setActive(true);
        //indicate that the MediaSession handles transport control commands
        // through its MediaSessionCompat.Callback.
        mediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        updateMetaData();
        // Attach Callback to receive MediaSession updates
        mediaSessionCompat.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                resumeMedia();
                buildNotification(PlaybackStatus.PLAYING);
            }

            @Override
            public void onPause() {
                super.onPause();
                pauseMedia();
                buildNotification(PlaybackStatus.PAUSED);
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                skipToNext();
                updateMetaData();
                buildNotification(PlaybackStatus.PLAYING);
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                skipToPrevious();
                updateMetaData();
                buildNotification(PlaybackStatus.PLAYING);
            }

            @Override
            public void onStop() {
                super.onStop();
                removeNotification();
                //Stop the service
                stopSelf();
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
            }
        });
    }

    private void removeNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void buildNotification(PlaybackStatus playbackStatus) {
        int notificationAction = android.R.drawable.ic_media_pause;//needs to be initialized
        PendingIntent play_pauseAction = null;

        //Build a new notification according to the current state of the MediaPlayer
        if (playbackStatus == PlaybackStatus.PLAYING) {
            notificationAction = android.R.drawable.ic_media_pause;
            //create the pause action
            play_pauseAction = playbackAction(1);
        } else if (playbackStatus == PlaybackStatus.PAUSED) {
            notificationAction = android.R.drawable.ic_media_play;
            //create the play action
            play_pauseAction = playbackAction(0);
        }

        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(),
                R.drawable.image5); //replace with your own image

        // Create a new Notification
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "80001";
            NotificationChannel notificationChannel = new NotificationChannel(channelId, "NoTUII", NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.enableVibration(true);
            notificationManager.createNotificationChannel(notificationChannel);
            notificationChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});


            NotificationCompat.Builder notificationBuilder = (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                    .setShowWhen(false)
                    // Set the Notification style
                    .setColor(getResources().getColor(R.color.colorPrimary))
                    // Set the large and small icons
                    .setLargeIcon(largeIcon)
                    .setSmallIcon(android.R.drawable.stat_sys_headset)
                    // Set Notification content information
                    .setContentText(activeAudio.getArtist())
                    .setContentTitle(activeAudio.getAlbum())
                    .setContentInfo(activeAudio.getTitle())
                    .setChannelId(channelId)
                    // Add playback actions
                    .addAction(android.R.drawable.ic_media_previous, "previous", playbackAction(3))
                    .addAction(notificationAction, "pause", play_pauseAction)
                    .addAction(android.R.drawable.ic_media_next, "next", playbackAction(2));

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notificationBuilder.build());

        } else {
            NotificationCompat.Builder notificationBuilder = (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                    .setShowWhen(false)
                    // Set the Notification style
                    .setColor(getResources().getColor(R.color.colorPrimary))
                    // Set the large and small icons
                    .setLargeIcon(largeIcon)
                    .setSmallIcon(android.R.drawable.stat_sys_headset)
                    // Set Notification content information
                    .setContentText(activeAudio.getArtist())
                    .setContentTitle(activeAudio.getAlbum())
                    .setContentInfo(activeAudio.getTitle())
                    // Add playback actions
                    .addAction(android.R.drawable.ic_media_previous, "previous", playbackAction(3))
                    .addAction(notificationAction, "pause", play_pauseAction)
                    .addAction(android.R.drawable.ic_media_next, "next", playbackAction(2));

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notificationBuilder.build());

        }


    }

    private void handleIncomingActions(Intent playbackAction) {
        if (playbackAction == null || playbackAction.getAction() == null) return;

        String actionString = playbackAction.getAction();
        if (actionString.equalsIgnoreCase(ACTION_PLAY)) {
            transportControls.play();
        } else if (actionString.equalsIgnoreCase(ACTION_PAUSE)) {
            transportControls.pause();
        } else if (actionString.equalsIgnoreCase(ACTION_NEXT)) {
            transportControls.skipToNext();
        } else if (actionString.equalsIgnoreCase(ACTION_PREVIOUS)) {
            transportControls.skipToPrevious();
        } else if (actionString.equalsIgnoreCase(ACTION_STOP)) {
            transportControls.stop();
        }
    }

    private PendingIntent playbackAction(int actionNumber) {
        Intent playbackAction = new Intent(this, MediaPlayerService.class);
        switch (actionNumber) {
            case 0:
                // Play
                playbackAction.setAction(ACTION_PLAY);
                return PendingIntent.getService(this, actionNumber, playbackAction, 0);
            case 1:
                // Pause
                playbackAction.setAction(ACTION_PAUSE);
                return PendingIntent.getService(this, actionNumber, playbackAction, 0);
            case 2:
                // Next track
                playbackAction.setAction(ACTION_NEXT);
                return PendingIntent.getService(this, actionNumber, playbackAction, 0);
            case 3:
                // Previous track
                playbackAction.setAction(ACTION_PREVIOUS);
                return PendingIntent.getService(this, actionNumber, playbackAction, 0);
            default:
                break;
        }
        return null;
    }


    private void skipToPrevious() {

        if (audioIndex == 0) {
            //if first in playlist
            //set index to the last of audioList
            audioIndex = audioList.size() - 1;
            activeAudio = audioList.get(audioIndex);
        } else {
            //get previous in playlist
            activeAudio = audioList.get(--audioIndex);
        }

        //Update stored index
        new StorageUtil(getApplicationContext()).storeAudioIndex(audioIndex);

        stopMedia();
        //reset mediaPlayer
        mediaPlayer.reset();
        initMediaPlayer();
    }

    private void skipToNext() {

        if (audioIndex == audioList.size() - 1) {
            //if last in playlist
            audioIndex = 0;
            activeAudio = audioList.get(audioIndex);
        } else {
            //get next in playlist
            activeAudio = audioList.get(++audioIndex);
        }

        //Update stored index
        new StorageUtil(getApplicationContext()).storeAudioIndex(audioIndex);

        stopMedia();
        //reset mediaPlayer
        mediaPlayer.reset();
        initMediaPlayer();
    }


    private void updateMetaData() {
        Bitmap albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.splash);
        mediaSessionCompat.setMetadata(new MediaMetadataCompat.Builder().putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio.getAlbum())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio.getTitle())
                .build());
    }

    private void register_playNewAudio() {
        IntentFilter intentFilter = new IntentFilter(MainActivity.Broadcast_PLAY_NEW_AUDIO);
        registerReceiver(playNewAudio, intentFilter);
    }

    public class LocalBinder extends Binder {

        public MediaPlayerService getService() {
            return MediaPlayerService.this;
        }
    }
}
