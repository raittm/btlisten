package raittm.example.btlisten;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothServerSocket;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "btListen";
    private static final boolean D=true;

    private final UUID DATA_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final UUID CONTROL_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fc");

    int mState;

    public static final int CMD_FILEINFO=1;
    public static final int CMD_BUFFERSTART=2;
    public static final int CMD_BUFFEREND=3;
    public static final int CMD_PLAY=4;
    public static final int CMD_STOP=5;
    public static final int CMD_VOLUMEUP=6;
    public static final int CMD_VOLUMEDOWN=7;
    public static final int CMD_FILESIZE=8;
    public static final int CMD_DISCONNECT=9;
    public static final int CMD_PAUSE=10;
    public static final int CMD_HEARTBEAT=11;

    public static final int STS_PLAYFINISHED=101;
    public static final int STS_ACK=102;
    public static final int STS_CONNECTED=103;
    public static final int STS_DISCONNECTED=104;
    public static final int STS_CURRENTPOS=105;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    PowerManager.WakeLock wakeLock;
    NotificationManager nm;

    BluetoothAdapter mBluetoothAdapter=null;

    AcceptThread mAcceptThread=null;
    ControlThread mControlThread=null;

    DataConnectThread mDataConnectThread=null;
    DataThread mDataThread=null;

    PlayThread mPlayThread=null;

    AudioManager am=null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PowerManager powerManager=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock= powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"btStream");
        wakeLock.acquire();

        nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter==null)
        {
            Toast.makeText(this, "No bluetooth adapter", Toast.LENGTH_SHORT).show();
        }

        if (!mBluetoothAdapter.isEnabled())
        {
            Toast.makeText(this, "Please enable bluetooth", Toast.LENGTH_LONG).show();
            //Intent enableBtIntent=new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            //startActivityForResult(enableBtIntent, 1);
            finish();
        }
        else {

            mAcceptThread = new AcceptThread();
            mAcceptThread.start();

            mState = STATE_LISTEN;

        }

        am=(AudioManager)this.getSystemService(Context.AUDIO_SERVICE);

    }

    public void cleanUp()
    {
        if (D) Log.d(TAG, "Cleaning up");

        if (mPlayThread != null) {
            mPlayThread.cancel();
            mPlayThread = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        if (mControlThread != null) {
            mControlThread.cancel();
            mControlThread = null;
        }
        if (mDataThread != null) {
            mDataThread.cancel();
            mDataThread = null;
        }
        if (mDataConnectThread != null) {
            mDataConnectThread.cancel();
            mDataConnectThread = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedState) {
        super.onRestoreInstanceState(savedState);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (D) Log.d(TAG, "Start");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        wakeLock.release();

        if (D) Log.d(TAG, "Destroying");

        nm.cancelAll();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (D) Log.d(TAG, "Stopping");

        PendingIntent pi= PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        NotificationCompat.Builder n = new NotificationCompat.Builder(this)
                .setContentTitle("btListen is running")
                .setContentText("Click here to open btStream")
                .setSmallIcon(R.drawable.ic_bluetooth_audio_white_24dp)
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(1, n.build());

        //
        // don't kill the threads as then music streaming will stop
        //
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (D) Log.d(TAG, "Resuming");

        nm.cancelAll();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_exit) {

            cleanUp();
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            try {
                tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("BTlisten", CONTROL_UUID);
            } catch (IOException e) { Log.e(TAG, "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN mAcceptThread");

            BluetoothSocket socket;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (MainActivity.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                mControlThread = new ControlThread(socket);
                                mControlThread.start();

                                mState=STATE_CONNECTED;

                                if (mAcceptThread != null) {
                                    mAcceptThread.cancel();
                                    mAcceptThread = null;
                                }

                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Problem closing accepted socket");
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {Log.e(TAG, "Problem closing server socket");}
        }
    }

    private class ControlThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final DataInputStream dis;
        private final DataOutputStream dos;

        private boolean finished=false;

        public ControlThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn=null;
            OutputStream tmpOut=null;
            try {
                tmpIn=socket.getInputStream();
                tmpOut=socket.getOutputStream();
            } catch (IOException e) {}

            mmInStream=tmpIn;
            mmOutStream=tmpOut;

            dis=new DataInputStream(mmInStream);
            dos=new DataOutputStream(mmOutStream);
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN ControlThread");

            int msgLength=0;
            int avail=0;
            boolean waitingForLength=true;
            while (!finished) {
                //synchronized (mBluetoothAdapter)
                {
                    try {
                        if (waitingForLength) {
                            msgLength = dis.readInt();

                            waitingForLength = false;
                        } else {
                            if (dis.available() > (msgLength)) {

                                int n = msgLength;
                                char c;
                                StringBuffer s = new StringBuffer(n);

                                s.delete(0, s.length());
                                while (n > 0) {
                                    s.append(dis.readChar());
                                    n--;
                                }
                                waitingForLength = true;

                                String[] keyvalue = s.toString().split("=");
                                if (D) Log.d(TAG, "" + keyvalue[0] + " " + keyvalue[1]);

                                if (keyvalue[0].equals("NowPlaying")) {
                                    mHandler.obtainMessage(CMD_STOP).sendToTarget();
                                    mHandler.obtainMessage(CMD_FILEINFO, keyvalue[1]).sendToTarget();
                                } else if (keyvalue[0].equals("FileSize")) {
                                    mHandler.obtainMessage(CMD_FILESIZE, keyvalue[1]).sendToTarget();
                                } else if (keyvalue[0].equals("Cmd")) {
                                    if (keyvalue[1].equals("BufferStart")) {
                                        mHandler.obtainMessage(CMD_BUFFERSTART).sendToTarget();
                                    } else if (keyvalue[1].equals("BufferEnd")) {
                                        mHandler.obtainMessage(CMD_BUFFEREND).sendToTarget();
                                    } else if (keyvalue[1].equals("Play")) {
                                        mHandler.obtainMessage(CMD_PLAY).sendToTarget();
                                    } else if (keyvalue[1].equals("Stop")) {
                                        mHandler.obtainMessage(CMD_STOP).sendToTarget();
                                    } else if (keyvalue[1].equals("VolumeUp")) {
                                        mHandler.obtainMessage(CMD_VOLUMEUP).sendToTarget();
                                    } else if (keyvalue[1].equals("VolumeDown")) {
                                        mHandler.obtainMessage(CMD_VOLUMEDOWN).sendToTarget();
                                    } else if (keyvalue[1].equals("Disconnect")) {
                                        mHandler.obtainMessage(CMD_DISCONNECT).sendToTarget();
                                    } else if (keyvalue[1].equals("Pause")) {
                                        mHandler.obtainMessage(CMD_PAUSE).sendToTarget();
                                    }
                                }

                            }
                        }
                        Thread.sleep(100);
                    } catch (IOException e) {
                        Log.e(TAG, "Problem reading control stream " + e);

                        mHandler.obtainMessage(CMD_DISCONNECT).sendToTarget();

                        break;
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        public void cancel() {
            try {
                finished=true;

                //if (dis!=null) dis.close();
                //if (dos!=null) dos.close();
                //mmInStream.close();
                //mmOutStream.close();
                mmSocket.close();
            } catch (IOException e) {Log.d(TAG, "Problem cancelling ControlThread");}
        }

        public void sendPlayFinished() {
            String ss="Status=PlayFinished";
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
        public void sendCurrentPos(int p) {
            String ss="CurrentPos="+p;
            try {
                if (D) Log.d(TAG,""+ss);
                dos.writeInt(ss.length());
                dos.writeChars(ss);
            } catch (IOException e) {}
        }
    }

    Handler mHandler = new Handler() {
        String s;
        long expectedFileSize=0;

        @Override
        public void handleMessage(Message msg) {
            TextView nowPlayingLabel = (TextView) findViewById(R.id.nowPlaying);

            synchronized (MainActivity.this)
            {
            switch (msg.what) {
                case CMD_FILEINFO:
                    s = ((String) msg.obj);
                    nowPlayingLabel.setText(s);

                    break;
                case CMD_FILESIZE:
                    expectedFileSize = Long.parseLong((String) msg.obj);

                    break;
                case CMD_BUFFERSTART:
                    if (mDataThread != null) {
                        mDataThread.cancel();
                        mDataThread = null;
                    }
                    if (mDataConnectThread != null) {
                        mDataConnectThread.cancel();
                        mDataConnectThread = null;
                    }

                    mDataConnectThread = new DataConnectThread(mControlThread.mmSocket.getRemoteDevice(), s, expectedFileSize);
                    mDataConnectThread.start();

                    break;
                case CMD_BUFFEREND:
                    if (mDataThread != null) {
                        mDataThread.cancel();
                        mDataThread = null;
                    }

                    break;
                case CMD_PLAY:
                    // start a PlayThread
                    if (mPlayThread != null) {
                        mPlayThread.cancel();

                        while (mPlayThread.isAlive()) ;

                        mPlayThread = null;
                    }
                    mPlayThread = new PlayThread(new File(getCacheDir(), s));
                    mPlayThread.start();
                    break;
                case CMD_STOP:
                    if (mPlayThread != null) {
                        mPlayThread.cancel();

                        while (mPlayThread.isAlive()) ;

                        mPlayThread = null;
                    }
                    break;
                case CMD_PAUSE:
                    if (mPlayThread != null) {
                        if (mPlayThread.mediaPlayer.isPlaying()) {
                            mPlayThread.mediaPlayer.pause();
                        } else {
                            mPlayThread.mediaPlayer.start();
                        }

                    }
                    break;
                case CMD_VOLUMEUP:
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC) + AudioManager.ADJUST_RAISE, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                    break;
                case CMD_VOLUMEDOWN:
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC) + AudioManager.ADJUST_LOWER, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                    break;
                case CMD_DISCONNECT:
                    cleanUp();

                    nowPlayingLabel.setText("Disconnected");

                    mAcceptThread = new AcceptThread();
                    mAcceptThread.start();

                    mState = STATE_LISTEN;
                    break;
                case STS_PLAYFINISHED:
                    if (mControlThread != null) mControlThread.sendPlayFinished();

                    if (mDataThread != null) {
                        mDataThread.cancel();
                        mDataThread = null;
                    }

                    nowPlayingLabel.setText("Nothing to play");
                    break;

                case STS_CONNECTED:
                    nowPlayingLabel.setText("Connected - nothing to play");
                    break;

                case STS_CURRENTPOS:
                    //if (mControlThread!=null) mControlThread.sendCurrentPos((Integer)msg.obj);
                    break;

                default:

            }
        }
        }
    };

    private class DataConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        private final String fname;
        private final long fsize;

        public DataConnectThread(BluetoothDevice device, String s, long l) {
            BluetoothSocket tmp=null;
            mmDevice = device;
            try {
                tmp=device.createRfcommSocketToServiceRecord(DATA_UUID);
            } catch (IOException e) {}
            mmSocket=tmp;

            fname=s;
            fsize=l;

        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN DataConnectThread");

            mBluetoothAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException connectException) {
                try {
                    mmSocket.close();
                } catch (IOException closeException) {Log.d(TAG,"Problem closing socket in DataConnectThread");}
                return;
            }

            mDataThread = new DataThread(mmSocket, fname, fsize);
            mDataThread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
            mDataThread.start();

            if (D) Log.d(TAG,"END DataConnectThread");
        }

        public void cancel() {
            if (D) Log.d(TAG,"Cancelling DataConnectThread");
            try {
                mmSocket.close();
            } catch (IOException e) {Log.d(TAG,"Problem cancelling DataConnect socket");}
        }
    }

    private class DataThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        private FileOutputStream fos=null;

        private boolean finished=false;
        private boolean buffering=false;

        int totbytes=0;
        long mFileSize=0;

        File localFile=null;

        public DataThread(BluetoothSocket socket, String remoteFilename, long remoteFileSize) {
            mmSocket = socket;
            InputStream tmpIn=null;
            OutputStream tmpOut=null;
            try {
                tmpIn=socket.getInputStream();
                tmpOut=socket.getOutputStream();
            } catch (IOException e) {}

            mmInStream=tmpIn;
            mmOutStream=tmpOut;

            localFile=new File(getCacheDir(),remoteFilename);

            if (localFile.exists()) localFile.delete();

            try {
                localFile.createNewFile();

                fos = new FileOutputStream(localFile, true);
            } catch (IOException e) {
                Log.d(TAG, "Can't create temp file");
            }

            mFileSize=remoteFileSize;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN DataThread");

            byte[] buffer=new byte[32*1024];

            int bytes;
            int avail;

            finished=false;
            while (!finished) {
                try {
                    avail=mmInStream.available();
                    if (avail > 0) {
                        //synchronized (mBluetoothAdapter)
                        {
                            if (avail > buffer.length) {
                                bytes = mmInStream.read(buffer, 0, buffer.length);
                            } else {
                                bytes = mmInStream.read(buffer, 0, avail);
                            }
                        }
                        totbytes += bytes;

                        //if (D) Log.d(TAG, "Read " + totbytes);

                        fos.write(buffer, 0, bytes);

                        if (totbytes >= mFileSize) {
                            if (D) Log.d(TAG, "Finished buffering in run()");
                            finished=true;
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Problem reading data stream ");
                    finished=true;
                }
            }

            try {
                if (fos != null) {
                    //fos.flush();
                    fos.close();
                    fos = null;
                }
            } catch (IOException e) {Log.d(TAG,"Problem closing stream");}

            if (D) Log.d(TAG,"END DataThread");
        }
/*
        TimerTask timerDelayStopBuffering;
        Timer timer;

        class TimerDelayStopBuffering extends TimerTask
        {
            public TimerDelayStopBuffering()
            {
            }

            @Override
            public void run()
            {
                if (D) Log.d(TAG, "Finished buffering, read " + totbytes);

                buffering=false;

                try {
                    if (fos!=null) { fos.flush();fos.close(); fos=null;}
                } catch (IOException e) {Log.d(TAG,"Problem in TimerDelayStopBuffering");}

            }
        }

        public void stopBuffering() {
            if (D) Log.d(TAG, "Stop buffering");

            timer=new Timer();
            timerDelayStopBuffering = new TimerDelayStopBuffering();
            timer.schedule(timerDelayStopBuffering, 5000);

            if (D) Log.d(TAG,"Delaying");
        }

*/
        public void cancel() {
            if (D) Log.d(TAG,"Cancelling DataThread");
            finished=true;
            try {
                mmSocket.close();
            } catch (IOException e) {Log.d(TAG,"Problem cancelling DataThread");}
        }


    }

    private class PlayThread extends Thread {
        private FileInputStream fis = null;
        private boolean finished = false;
        private boolean starting = true;

        private int totalBytes;
        private int lastBytes;

        File musicFile=null;

        MediaPlayer mediaPlayer=null;

        TimerTask timerOneSecond;
        Timer timer;

        public PlayThread(File f) {

            lastBytes =0;
            totalBytes = 0;
            lastBytes=0;

            mediaPlayer=new MediaPlayer();

            musicFile=f;

        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN PlayThread");

            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    try {
                        if (fis!=null) fis.close();
                        fis = new FileInputStream(musicFile);

                        totalBytes = fis.available();

                        if (D) Log.d(TAG, "Playing " + lastBytes + "-" + totalBytes);

                        if (lastBytes==totalBytes) {
                            finished = true;

                            if (totalBytes!=mDataThread.mFileSize) {
                                if (D) Log.d(TAG, "Didn't get all bytes for file - bluetooth read/write blocked?");
                            }

                            mHandler.obtainMessage(STS_PLAYFINISHED).sendToTarget();
                        }
                        else {
                            mediaPlayer.reset();
                            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                            mediaPlayer.setDataSource(fis.getFD(), lastBytes, totalBytes);
                            mediaPlayer.prepare();
                            mediaPlayer.start();

                            lastBytes = totalBytes;
                        }

                    } catch (IOException e) {
                        Log.e(TAG, "Problem playing next audio fragment");

                        // due to end of file or interrupted stream
                    }

                }
            });

            timer=new Timer();

            while (!finished) {
                try {
                    if (starting && musicFile!=null) {
                        if (musicFile.length() > (128*1024)) {
                            fis=new FileInputStream(musicFile);
                            totalBytes =fis.available();

                            if (D) Log.d(TAG, "Playing " + lastBytes + "-" + totalBytes);

                            mediaPlayer.reset();
                            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                            mediaPlayer.setDataSource(fis.getFD(), lastBytes, totalBytes - lastBytes);
                            mediaPlayer.prepare();
                            mediaPlayer.start();

                            timerOneSecond = new TimerOneSecondHandler();
                            timer.scheduleAtFixedRate(timerOneSecond, 1000, 1000);

                            lastBytes=totalBytes;

                            starting=false;
                        }
                    }

                    Thread.sleep(100);
                } catch (InterruptedException e) {
                } catch (IOException ee) {Log.d(TAG,"Problem playing stream");}
            }
            if (D) Log.d(TAG,"Finished playback");

            timer.cancel();

            if (musicFile!=null) {
                if (musicFile.exists()) {
                    musicFile.delete();
                }
            }

            try {
                if (fis != null) {
                    fis.close();
                    fis = null;
                }
            } catch (IOException e) {
                Log.d(TAG, "Problem closing musicfile inputstream");
            }
            if (D) Log.d(TAG,"END PlayThread");
        }

        public void cancel() {
            Log.d(TAG, "Cancelling PlayThread");

            if (timer!=null) timer.cancel();

            finished = true;

            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

        }

        class TimerOneSecondHandler extends TimerTask
        {
            int zero;
            int totalpos;
            int lastpos;

            public TimerOneSecondHandler()
            {
                zero=0;
                totalpos=0;
                lastpos=0;
            }

            @Override
            public void run()
            {
                if (mediaPlayer!=null) {
                    int pos=mediaPlayer.getCurrentPosition();

                    //Log.i(TAG,""+zero+" "+lastpos+" "+totalpos);

                    int Hours = totalpos / (1000*60*60);
                    int Minutes = (totalpos % (1000*60*60)) / (1000*60);
                    int Seconds = ((totalpos % (1000*60*60)) % (1000*60)) / 1000;

                    Log.i(TAG,""+String.format("%02d",Hours)+":"+String.format("%02d",Minutes)+":"+String.format("%02d",Seconds));

                    if (lastpos>pos) {
                        zero=totalpos;
                    }
                    totalpos = zero + pos;
                    lastpos=pos;

                    mHandler.obtainMessage(STS_CURRENTPOS, totalpos).sendToTarget();

                }
            }
        }
    }


}
