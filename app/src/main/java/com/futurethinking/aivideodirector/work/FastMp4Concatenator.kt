package com.futurethinking.aivideodirector.work

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Low-memory path for compatible MP4/H.264/AAC inputs. Copies compressed
 * samples directly instead of decoding and re-encoding them.
 */
object FastMp4Concatenator {
    private data class Track(val index:Int,val format:MediaFormat)

    fun tryConcatenate(paths:List<String>,output:File,onProgress:(Int)->Unit):Boolean{
        if(paths.size<2)return false
        return runCatching{
            val all=paths.map{inspect(it)}
            if(!compatibleAll(all))return@runCatching false
            output.parentFile?.mkdirs()
            if(output.exists())output.delete()
            val muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started=false
            try{
                val firstVideo=all.first().first!!
                val firstAudio=all.first().second!!
                val videoOut=muxer.addTrack(firstVideo.format)
                val audioOut=muxer.addTrack(firstAudio.format)
                if(Build.VERSION.SDK_INT>=24){
                    val rotation=firstVideo.format.intValue(MediaFormat.KEY_ROTATION,0)
                    if(rotation==90||rotation==180||rotation==270)muxer.setOrientationHint(rotation)
                }
                muxer.start();started=true
                var offsetUs=0L
                paths.forEachIndexed{index,path->
                    copyInput(muxer,path,all[index].first!!,all[index].second!!,videoOut,audioOut,offsetUs)
                    offsetUs+=durationUs(path)
                    onProgress((index+1)*100/paths.size)
                }
            }finally{
                if(started)runCatching{muxer.stop()}
                runCatching{muxer.release()}
            }
            output.exists()&&output.length()>8192L
        }.getOrDefault(false)
    }

    private fun inspect(path:String):Pair<Track?,Track?>{
        val extractor=MediaExtractor()
        try{
            extractor.setDataSource(path)
            var video:Track?=null;var audio:Track?=null
            for(i in 0 until extractor.trackCount){
                val f=extractor.getTrackFormat(i)
                when(f.getString(MediaFormat.KEY_MIME).orEmpty()){
                    "video/avc"->if(video==null)video=Track(i,f)
                    "audio/mp4a-latm"->if(audio==null)audio=Track(i,f)
                }
            }
            return video to audio
        }finally{extractor.release()}
    }

    private fun compatibleAll(all:List<Pair<Track?,Track?>>):Boolean{
        if(all.any{it.first==null||it.second==null})return false
        val fv=all.first().first!!.format;val fa=all.first().second!!.format
        return all.drop(1).all{
            compatibleVideo(fv,it.first!!.format)&&compatibleAudio(fa,it.second!!.format)
        }
    }

    private fun compatibleVideo(a:MediaFormat,b:MediaFormat)=
        sameString(a,b,MediaFormat.KEY_MIME)&&
        sameInt(a,b,MediaFormat.KEY_WIDTH)&&
        sameInt(a,b,MediaFormat.KEY_HEIGHT)&&
        sameInt(a,b,MediaFormat.KEY_ROTATION,0)&&
        sameBytes(a,b,"csd-0")&&sameBytes(a,b,"csd-1")

    private fun compatibleAudio(a:MediaFormat,b:MediaFormat)=
        sameString(a,b,MediaFormat.KEY_MIME)&&
        sameInt(a,b,MediaFormat.KEY_SAMPLE_RATE)&&
        sameInt(a,b,MediaFormat.KEY_CHANNEL_COUNT)&&
        sameBytes(a,b,"csd-0")&&sameBytes(a,b,"csd-1")

    private fun sameString(a:MediaFormat,b:MediaFormat,key:String)=
        a.getString(key).orEmpty()==b.getString(key).orEmpty()

    private fun sameInt(a:MediaFormat,b:MediaFormat,key:String,default:Int=Int.MIN_VALUE)=
        a.intValue(key,default)==b.intValue(key,default)

    private fun sameBytes(a:MediaFormat,b:MediaFormat,key:String):Boolean{
        val x=a.getByteBuffer(key)?.duplicate();val y=b.getByteBuffer(key)?.duplicate()
        if(x==null||y==null)return x==null&&y==null
        if(x.remaining()!=y.remaining())return false
        while(x.hasRemaining())if(x.get()!=y.get())return false
        return true
    }

    private fun durationUs(path:String):Long{
        val extractor=MediaExtractor()
        return try{
            extractor.setDataSource(path)
            var max=0L
            for(i in 0 until extractor.trackCount){
                val d=extractor.getTrackFormat(i).longValue(MediaFormat.KEY_DURATION,0L)
                if(d>max)max=d
            }
            max
        }finally{extractor.release()}
    }

    private fun copyInput(
        muxer:MediaMuxer,path:String,video:Track,audio:Track,
        videoOut:Int,audioOut:Int,offsetUs:Long
    ){
        val extractor=MediaExtractor()
        try{
            extractor.setDataSource(path)
            val basePts=minOf(firstPts(extractor,video.index),firstPts(extractor,audio.index))
            extractor.selectTrack(video.index);extractor.selectTrack(audio.index)
            extractor.seekTo(0L,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var capacity=4*1024*1024
            var buffer=ByteBuffer.allocateDirect(capacity)
            val info=MediaCodec.BufferInfo()
            while(true){
                buffer.clear()
                val sourceTrack=extractor.sampleTrackIndex
                if(sourceTrack<0)break
                val size=extractor.readSampleData(buffer,0)
                if(size<0)break
                if(size>capacity){
                    capacity=size
                    buffer=ByteBuffer.allocateDirect(capacity)
                }
                info.offset=0
                info.size=size
                info.presentationTimeUs=(extractor.sampleTime-basePts+offsetUs).coerceAtLeast(0L)
                info.flags=extractor.sampleFlags
                if(sourceTrack==video.index)muxer.writeSampleData(videoOut,buffer,info)
                else if(sourceTrack==audio.index)muxer.writeSampleData(audioOut,buffer,info)
                extractor.advance()
            }
        }finally{extractor.release()}
    }

    private fun firstPts(extractor:MediaExtractor,index:Int):Long{
        extractor.unselectTrack(index)
        extractor.selectTrack(index)
        extractor.seekTo(0L,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return extractor.sampleTime.coerceAtLeast(0L)
    }

    private fun MediaFormat.intValue(key:String,default:Int):Int=
        if(containsKey(key))getInteger(key)else default

    private fun MediaFormat.longValue(key:String,default:Long):Long=
        if(containsKey(key))getLong(key)else default
}
