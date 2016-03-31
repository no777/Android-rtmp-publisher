package tv.lycam.rtmp;

import android.media.MediaCodec;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Created by a3213105 on 15/9/8.
 */
public class RTMPSender {
    public long mNativeRTMP = 0;
    private Thread worker;
    private static final String TAG = "gs-sender";
    private boolean loop;
    private long btye_sended = 0;

    private int lastAddAudioTs = 0;
    private int lastAddVideoTs = 0;

    private boolean inited = false;
    private long ideaStartTime;
    private long systemStartTime;

    public boolean needResetTs = false;
    private volatile boolean dropNoneIDRFrame = false;

    private static final int FIRST_OPEN = 3;
    private static final int FROM_AUDIO = 8;
    private static final int FROM_VIDEO = 6;

    private long lastRefreshTime;
    private long lastSendVideoDts;
    private long lastSendAudioDts;
    private long lastSendVideoTs;
    private long lastSendAudioTs;
    private int dropAudioCount;
    private int dropVideoCount;


    //    private LinkedList<KSYFlvData> recordQueue;
    private PriorityQueue<KSYFlvData> recordPQueue;

    private SenderStatData statData = new SenderStatData();


    private Object mutex = new Object();


    public RTMPSender() {
        loadLibs();
        recordPQueue = new PriorityQueue<>(10, new Comparator<KSYFlvData>() {
            @Override
            public int compare(KSYFlvData lhs, KSYFlvData rhs) {
                return lhs.dts - rhs.dts;
            }
        });
    }

    public long getByteSended() {
        return btye_sended;
    }

    public void writeAudioSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) throws Exception {

        int pts = (int) (bi.presentationTimeUs);
        int size = bi.size;
        byte[] dst = null;
        try {
            dst = new byte[size];
            bb.get(dst);

        } catch (Exception e) {

        }

        if(dst!=null) {
            KSYFlvData data = new KSYFlvData();
            data.byteBuffer = dst;
            data.size = size;
            data.dts = pts;
            data.type = KSYFlvData.FLV_TYTPE_AUDIO;
            addToQueue(data,FROM_AUDIO);

        }
    }

    public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) throws Exception {

        int pts = (int) (bi.presentationTimeUs);
        int size = bi.size;
        byte[] dst = null;
        try {
            dst = new byte[size];
            bb.get(dst);

//            dst = bb.array();
        } catch (Exception e) {

        }

        if(dst!=null) {
//            Log.i(TAG,"_write_rtmp_video pts=" + pts + " size=" + size);
            KSYFlvData data = new KSYFlvData();
            data.byteBuffer = dst;
            data.size = size;
            data.dts = pts;
            data.type = KSYFlvData.FLV_TYPE_VIDEO;

            addToQueue(data,FROM_VIDEO);

        }
    }

    public void initSender(boolean has_video, boolean isAnnexb, boolean has_audio, boolean isRaw, String url) {
        _set_output_url(has_video ? 1 : 0, isAnnexb ? 1 : 0, has_audio ? 1 : 0, isRaw ? 1 : 0, url);
    }


//    private void sendPoorNetworkMessage(int reason) {
//        if (System.currentTimeMillis() - lastPoorNotificationTime > 3000 && recordHandler != null) {
//            recordHandler.sendEmptyMessage(reason);
//            lastPoorNotificationTime = System.currentTimeMillis();
//        }
//    }

    private void removeToNextIDRFrame(PriorityQueue<KSYFlvData> recordPQueue) {
        if (recordPQueue.size() > 0) {
            KSYFlvData data = recordPQueue.remove();
            if (data.type == KSYFlvData.FLV_TYPE_VIDEO) {
                if (data.isKeyframe()) {
                    recordPQueue.add(data);
                } else {
                    statData.remove(data);
                    removeToNextIDRFrame(recordPQueue);
                }
            } else {
                statData.remove(data);
                removeToNextIDRFrame(recordPQueue);
            }
        }
    }

    private void removeQueue(PriorityQueue<KSYFlvData> recordPQueue) {
        if (recordPQueue.size() > 0) {
            KSYFlvData data = recordPQueue.remove();
            if (data.type == KSYFlvData.FLV_TYPE_VIDEO && data.isKeyframe()) {
                removeToNextIDRFrame(recordPQueue);
            }
            statData.remove(data);
        }
    }
    //send data to server
    public synchronized void addToQueue(KSYFlvData ksyFlvData, int k) {
        if (ksyFlvData == null) {
            return;
        }
        if (ksyFlvData.size <= 0) {
            return;
        }
//        KsyMediaSource.sync.setAvDistance(lastAddAudioTs - lastAddVideoTs);
        // add video data
        synchronized (mutex) {
            if (recordPQueue.size() > statData.LEVEL1_QUEUE_SIZE) {
                removeQueue(recordPQueue);
//                sendPoorNetworkMessage(NetworkMonitor.OnNetworkPoorListener.CACHE_QUEUE_MAX);
            }
            if (k == FROM_VIDEO) { //视频数据
                if (needResetTs) {
//                    KsyMediaSource.sync.resetTs(lastAddAudioTs);
//                    Log.d(Constants.LOG_TAG, "lastAddAudioTs = " + lastAddAudioTs);
//                    Log.d(Constants.LOG_TAG, "lastAddVideoTs = " + lastAddVideoTs);
//                    Log.d(Constants.LOG_TAG, "ksyFlvData.dts = " + ksyFlvData.dts);
                    needResetTs = false;
                    lastAddVideoTs = lastAddAudioTs;
                    ksyFlvData.dts = lastAddVideoTs;
                }
//                vidoeFps.tickTock();
                lastAddVideoTs = ksyFlvData.dts;
//                Log.d(Constants.LOG_TAG, "video_enqueue = " + ksyFlvData.dts + " " + ksyFlvData.isKeyframe());
            } else if (k == FROM_AUDIO) {//音频数据
                lastAddAudioTs = ksyFlvData.dts;
            }
            statData.add(ksyFlvData);
            recordPQueue.add(ksyFlvData);
        }
    }

    public void waiting(KSYFlvData ksyFlvData) throws InterruptedException {
        if (ksyFlvData.type != KSYFlvData.FLV_TYTPE_AUDIO) {
            return;
        }
        long ts = ksyFlvData.dts;
        if (!inited) {
            ideaStartTime = ts;
            systemStartTime = System.currentTimeMillis();
            inited = true;
            return;
        }
        long ideaTime = System.currentTimeMillis() - systemStartTime + ideaStartTime;
        if (Math.abs(ideaTime - ts) > 100) {
            inited = false;
            return;
        }
        while (ts > ideaTime) {
            try {
                Thread.sleep(1);
                ideaTime = System.currentTimeMillis() - systemStartTime + ideaStartTime;
            }
            catch(Exception e){

            }
        }
    }
    public void startSender() {
        int ret = _open();;
        if(ret!=0) {
            Log.e(TAG, "connect to rtmp server error" + ret);
            return ;
        }
        worker = new Thread(new Runnable() {

            // @Override
            @Override
            public void run() {
                try {
                    cycle();
                }
                catch (Exception e){
                    e.printStackTrace();
                }

            }
        });
        loop = true;
        worker.start();
    }

    public void stopSender() {
        loop = false;
        _close();
        if (worker != null) {
            Log.i(TAG, "stop video worker thread");
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            worker = null;
        }
    }
    private boolean needDropFrame(KSYFlvData ksyFlv) {
        boolean dropFrame = false;
        int queueSize = recordPQueue.size();
        int dts = ksyFlv.dts;
        if (queueSize > statData.LEVEL2_QUEUE_SIZE || (dropNoneIDRFrame && ksyFlv.type == KSYFlvData.FLV_TYPE_VIDEO)) {
            dropFrame = true;
        }
        if (ksyFlv.type == KSYFlvData.FLV_TYPE_VIDEO) {
            lastSendVideoDts = dts;
            if (ksyFlv.isKeyframe()) {
                dropNoneIDRFrame = false;
                dropFrame = false;
            }
            if (dropFrame) {
                dropNoneIDRFrame = true;
            }
        } else {
            lastSendAudioDts = dts;
        }
        return dropFrame;
    }
    private void cycle() throws InterruptedException {
        while (!Thread.interrupted()) {

            if (statData.frame_video > statData.MIN_QUEUE_BUFFER && statData.frame_audio > statData.MIN_QUEUE_BUFFER || recordPQueue.size() > 30) {
                KSYFlvData ksyFlv = null;
                synchronized (mutex) {
                    if (recordPQueue.size() > 0) {
                        ksyFlv = recordPQueue.remove();
                    } else {
                        statData.clear();
                        continue;
                    }
                }
                statData.remove(ksyFlv);
                if (ksyFlv.type == KSYFlvData.FLV_TYPE_VIDEO) {
                    lastSendVideoTs = ksyFlv.dts;
                } else if (ksyFlv.type == KSYFlvData.FLV_TYTPE_AUDIO) {
                    lastSendAudioTs = ksyFlv.dts;
                }
                if (needDropFrame(ksyFlv)) {
                    statDropFrame(ksyFlv);
                } else {
                    lastRefreshTime = System.currentTimeMillis();
//                    waiting(ksyFlv);
//                    Log.e(TAG, "ksyFlv ts=" + ksyFlv.dts + " size=" + ksyFlv.size + " type=" + (ksyFlv.type == KSYFlvData.FLV_TYTPE_AUDIO ? "==ADO==" : "**VDO**"));
//                    int w = _write(ksyFlv.byteBuffer, ksyFlv.byteBuffer.length);
                    if(ksyFlv.type == KSYFlvData.FLV_TYPE_VIDEO ){
                        try {
                            int ret = _write_rtmp_video(ksyFlv.byteBuffer, ksyFlv.size, ksyFlv.dts);
                            if (ret < 0) {
                                Log.w(TAG, "write video data error:" + ret);
                            }
                        }catch (Exception e){
                            e.printStackTrace();
                        }

                    }
                    else{
                        try {
                            int ret = _write_rtmp_audio(ksyFlv.byteBuffer, ksyFlv.size, ksyFlv.dts);
                            if (ret < 0) {
                                Log.w(TAG, "write audio data error:" + ret);
                            }
                        }
                        catch (Exception e){
                            e.printStackTrace();
                        }
                    }

                    int ret = _loop();
                    if(ret<0) {
                        Log.w(TAG, "send rtmp packet error " + ret);
                    } else {
                        btye_sended += ret;
                        //Log.i(TAG, "send rtmp packet total " + btye_sended);
                    }
                    try {
                        Thread.sleep(10);
                    }
                    catch (Exception e){

                    }


//                    statBitrate(w, ksyFlv.type);
                }
            }
        }
    }

    private void statDropFrame(KSYFlvData dropped) {
        if (dropped.type == KSYFlvData.FLV_TYPE_VIDEO) {
            dropVideoCount++;
        } else if (dropped.type == KSYFlvData.FLV_TYTPE_AUDIO) {
            dropAudioCount++;
        }
        Log.d(TAG, "drop frame !!" + dropped.isKeyframe());
    }


    private void send_loop() {
        int ret = 0;
        while (loop && !Thread.interrupted()) {
            ret = _loop();
            if(ret<0) {
                Log.w(TAG, "send rtmp packet error " + ret);
            } else {
                btye_sended += ret;
                //Log.i(TAG, "send rtmp packet total " + btye_sended);
            }
        }
    }

    private void loadLibs() {
        System.loadLibrary("rtmp");
        Log.i(TAG, "rtmp.so loaded");
        System.loadLibrary("rtmpjni");
        Log.i(TAG, "rtmpjni.so loaded");

    }

    private native int _set_output_url(int havV, int isAnnexb, int hasA, int isRaw, String url);
    private native int _open();
    private native int _close();
    private native int _loop();
    private native int _write_rtmp_audio(byte[] dts, int size, int pts);
    private native int _write_rtmp_video(byte[] dts, int size, int pts);
}
