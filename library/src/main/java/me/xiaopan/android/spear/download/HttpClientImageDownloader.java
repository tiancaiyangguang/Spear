/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.android.spear.download;

import android.util.Log;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

import me.xiaopan.android.spear.Spear;
import me.xiaopan.android.spear.request.DownloadRequest;

/**
 * 使用HttpClient来访问网络的下载器
 */
public class HttpClientImageDownloader implements ImageDownloader {
	private static final String NAME = "HttpClientImageDownloader";
    private static final int BUFFER_SIZE = 8*1024;
    private static final int DEFAULT_WAIT_TIMEOUT = 60*1000;   // 默认从连接池中获取连接的最大等待时间
    private static final int DEFAULT_READ_TIMEOUT = 10*1000;   // 默认读取超时时间
    private static final int DEFAULT_CONNECT_TIMEOUT = 10*1000;    // 默认连接超时时间
    private static final int DEFAULT_MAX_ROUTE_CONNECTIONS = 400;    // 默认每个路由的最大连接数
    private static final int DEFAULT_MAX_CONNECTIONS = 800;  // 默认最大连接数
    private static final int DEFAULT_SOCKET_BUFFER_SIZE = 8192;  // 默认Socket缓存大小
    private static final int DEFAULT_MAX_RETRY_COUNT = 1;    // 默认最大重试次数
    private static final int DEFAULT_PROGRESS_CALLBACK_NUMBER = 10;    // 默认进度回调次数
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 6.0; WOW64) AppleWebKit/534.24 (KHTML, like Gecko) Chrome/11.0.696.16 Safari/534.24";

    private DefaultHttpClient httpClient;
	private Map<String, ReentrantLock> urlLocks;
    private int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;
    private int progressCallbackNumber = DEFAULT_PROGRESS_CALLBACK_NUMBER;

	public HttpClientImageDownloader() {
		this.urlLocks = Collections.synchronizedMap(new WeakHashMap<String, ReentrantLock>());
		BasicHttpParams httpParams = new BasicHttpParams();
        ConnManagerParams.setTimeout(httpParams, DEFAULT_WAIT_TIMEOUT);
        ConnManagerParams.setMaxConnectionsPerRoute(httpParams, new ConnPerRouteBean(DEFAULT_MAX_ROUTE_CONNECTIONS));
        ConnManagerParams.setMaxTotalConnections(httpParams, DEFAULT_MAX_CONNECTIONS);
        HttpConnectionParams.setTcpNoDelay(httpParams, true);
        HttpConnectionParams.setSoTimeout(httpParams, DEFAULT_READ_TIMEOUT);
        HttpConnectionParams.setConnectionTimeout(httpParams, DEFAULT_CONNECT_TIMEOUT);
        HttpConnectionParams.setSocketBufferSize(httpParams, DEFAULT_SOCKET_BUFFER_SIZE);
        HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setUserAgent(httpParams, DEFAULT_USER_AGENT);
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
        httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(httpParams, schemeRegistry), httpParams);
        httpClient.addRequestInterceptor(new GzipProcessRequestInterceptor());
        httpClient.addResponseInterceptor(new GzipProcessResponseInterceptor());
	}

    @Override
    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void setConnectTimeout(int connectTimeout) {
        HttpParams httpParams = httpClient.getParams();
        HttpConnectionParams.setConnectionTimeout(httpParams, connectTimeout);
    }

    @Override
    public void setProgressCallbackNumber(int progressCallbackNumber) {
        this.progressCallbackNumber = progressCallbackNumber;
    }

    /**
     * 获取一个URL锁，通过此锁可以防止重复下载
     * @param url 下载地址
     * @return URL锁
     */
	public synchronized ReentrantLock getUrlLock(String url){
		ReentrantLock urlLock = urlLocks.get(url);
		if(urlLock == null){
			urlLock = new ReentrantLock();
			urlLocks.put(url, urlLock);
		}
		return urlLock;
	}

	@Override
	public DownloadResult download(DownloadRequest request) {
        // 根据下载地址加锁，防止重复下载
        ReentrantLock urlLock = getUrlLock(request.getUri());
        urlLock.lock();

        DownloadResult result = null;
        int number = 0;
        while(true){
            // 如果已经取消了就直接结束
            if (request.isCanceled()) {
                if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "已取消下载 - 拿到锁之后" + "；" + request.getName());
                break;
            }

            // 如果缓存文件已经存在了就直接返回缓存文件
            File cacheFile = request.getCacheFile();
            if (cacheFile != null && cacheFile.exists()) {
                result = DownloadResult.createByFile(cacheFile, false);
                break;
            }

            try {
                result = realDownload(request);
                break;
            } catch (Throwable e) {
                boolean retry = (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) && number < maxRetryCount;
                if(retry){
                    number++;
                    if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "；" + "下载异常 - 再次尝试" + "；" + request.getName());
                }else{
                    if (Spear.isDebugMode()) Log.e(Spear.TAG, NAME + "；" + "下载异常 - 不再尝试" + "；" + request.getName());
                }
                e.printStackTrace();
                if(!retry){
                    break;
                }
            }
        }

        // 释放锁
        urlLock.unlock();
        return result;
	}

    private DownloadResult realDownload(DownloadRequest request) throws IOException {
        HttpResponse httpResponse;
        try {
            httpResponse = httpClient.execute(new HttpGet(request.getUri()));
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "创建连接失败："+e.getMessage() + "；" + request.getName());
            throw e;
        }
        if (request.isCanceled()) {
            releaseConnection(httpResponse);
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "已取消下载 - 获取Response之后" + "；" + request.getName());
            return null;
        }

        // 检查状态码
        StatusLine statusLine = httpResponse.getStatusLine();
        if(statusLine == null){
            releaseConnection(httpResponse);
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "获取状态行失败" + "；" + request.getName());
            return null;
        }
        int responseCode = statusLine.getStatusCode();
        if (responseCode != 200) {
            releaseConnection(httpResponse);
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "状态码异常："+responseCode + " " + httpResponse.getStatusLine().getReasonPhrase() + "；" + request.getName());
            return null;
        }

        // 检查内容长度
        int contentLength = 0;
        Header[] headers = httpResponse.getHeaders("Content-Length");
        if(headers != null && headers.length > 0){
            contentLength = Integer.valueOf(headers[0].getValue());
        }
        if (contentLength <= 0) {
            releaseConnection(httpResponse);
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "内容长度异常："+contentLength + "；" + request.getName());
            return null;
        }

        return readData(request, httpResponse, contentLength);
    }

    private DownloadResult readData(DownloadRequest request, HttpResponse httpResponse, int contentLength) throws IOException {
        File tempFile = null;
        if(request.getCacheFile() != null && request.getSpear().getConfiguration().getDiskCache().applyForSpace(contentLength)){
            tempFile = new File(request.getCacheFile().getPath()+".temp");
            if(!HttpUrlConnectionImageDownloader.createFile(request.getCacheFile())){
                tempFile = null;
            }
        }

        // 获取输入流后判断是否已取消
        InputStream inputStream;
        try {
            inputStream = httpResponse.getEntity().getContent();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "获取输入流时发生异常：" + e.getMessage() + "；" + request.getName());
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) Log.w(Spear.TAG, NAME + "：" + "读取输入流时发生异常，需要删除临时缓存文件，但删除失败：" + tempFile.getPath() + "；" + request.getName());
            throw e;
        }
        if (request.isCanceled()) {
            HttpUrlConnectionImageDownloader.close(inputStream);
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "已取消下载 - 获取输入流之后" + "；" + request.getName());
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) Log.w(Spear.TAG, NAME + "：" + "获取输入流之后发现取消，需要删除临时缓存文件，但删除失败："+tempFile.getPath() + "；" + request.getName());
            return null;
        }

        // 当不需要将数据缓存到本地的时候就使用ByteArrayOutputStream来存储数据
        OutputStream outputStream;
        if(tempFile != null){
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(tempFile, false), BUFFER_SIZE);
            } catch (FileNotFoundException e) {
                HttpUrlConnectionImageDownloader.close(inputStream);
                Log.w(Spear.TAG, NAME + "：" + "创建输出流时找不到文件了："+tempFile.getPath() + "；" + request.getName());
                throw e;
            }
        }else{
            outputStream = new ByteArrayOutputStream();
        }

        // 读取数据
        int completedLength = 0;
        boolean exception = false;
        try {
            completedLength = HttpUrlConnectionImageDownloader.readData(inputStream, outputStream, request, contentLength, progressCallbackNumber);
        } catch (IOException e) {
            exception = true;
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "读取数据时发生异常："+e.getMessage() + "；" + request.getName());
            throw e;
        }finally {
            HttpUrlConnectionImageDownloader.close(outputStream);
            HttpUrlConnectionImageDownloader.close(inputStream);
            if (exception && tempFile != null && tempFile.exists() && !tempFile.delete()) Log.w(Spear.TAG, NAME + "：" + "读取数据时发生异常，需要删除临时缓存文件，但删除失败："+tempFile.getPath() + "；" + request.getName());
        }
        if (request.isCanceled()) {
            if (Spear.isDebugMode()) Log.w(Spear.TAG, NAME + "：" + "已取消下载 - 读取完数据之后" + "；" + request.getName());
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) Log.w(Spear.TAG, NAME + "：" + "读取完数据之后发现取消了，需要删除临时缓存文件，但删除失败："+tempFile.getPath() + "；" + request.getName());
            return null;
        }

        if (Spear.isDebugMode()) Log.i(Spear.TAG, NAME + "：" + "下载成功" + "；" + "文件长度：" + completedLength + "/" + contentLength + "；" + request.getName());

        // 转换结果
        if(tempFile != null && tempFile.exists()){
            if(tempFile.renameTo(request.getCacheFile())){
                return DownloadResult.createByFile(request.getCacheFile(), true);
            }else{
                if (!tempFile.delete()) Log.w(Spear.TAG, NAME + "：" + "重命名失败，需要删除临时缓存文件，但删除失败："+tempFile.getPath() + "；" + request.getName());
                return null;
            }
        }else if(outputStream instanceof ByteArrayOutputStream){
            return DownloadResult.createByByteArray(((ByteArrayOutputStream) outputStream).toByteArray(), true);
        }else{
            return null;
        }
    }

    public static void releaseConnection(HttpResponse httpResponse){
        if(httpResponse == null){
            return;
        }

        HttpEntity httpEntity = httpResponse.getEntity();
        if(httpEntity == null){
            return;
        }

        InputStream inputStream = null;
        try {
            inputStream = httpEntity.getContent();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(inputStream == null){
            return;
        }

        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class GzipProcessRequestInterceptor implements HttpRequestInterceptor {
        /**
         * 头字段 - 接受的编码
         */
        public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";

        /**
         * 编码 - gzip
         */
        public static final String ENCODING_GZIP = "gzip";

        @Override
        public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
            //如果请求头中没有HEADER_ACCEPT_ENCODING属性就添加进去
            if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
                request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
            }
        }
    }

    private static class GzipProcessResponseInterceptor implements HttpResponseInterceptor {

        @Override
        public void process(HttpResponse response, HttpContext context) {
            final HttpEntity entity = response.getEntity();
            if(entity != null) {
                final Header encoding = entity.getContentEncoding();
                if (encoding != null) {
                    for (HeaderElement element : encoding.getElements()) {
                        if (element.getName().equalsIgnoreCase(GzipProcessRequestInterceptor.ENCODING_GZIP)) {
                            response.setEntity(new InflatingEntity(entity));
                            break;
                        }
                    }
                }
            }
        }

        private static class InflatingEntity extends HttpEntityWrapper {
            public InflatingEntity(HttpEntity wrapped) {
                super(wrapped);
            }

            @Override
            public InputStream getContent() throws IOException {
                return new GZIPInputStream(wrappedEntity.getContent());
            }

            @Override
            public long getContentLength() {
                return -1;
            }
        }
    }
}
