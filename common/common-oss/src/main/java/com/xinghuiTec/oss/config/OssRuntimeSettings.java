package com.xinghuiTec.oss.config;

import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.BusinessConfigSource.Snapshot;
import com.xinghuiTec.exception.ServiceException;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageProperties;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.platform.MinioFileStorage;
import org.dromara.x.file.storage.core.platform.MinioFileStorageClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;

@Component
public class OssRuntimeSettings {
    private final FileStorageService storage;
    private final ObjectProvider<BusinessConfigSource> source;
    public OssRuntimeSettings(FileStorageService storage,ObjectProvider<BusinessConfigSource> source) {
        this.storage=storage;this.source=source;
    }
    private FileStorageProperties.MinioConfig configuration(Snapshot snapshot) {
        var params=snapshot.parameters();
        validateParameters(params);
        String endpoint=params.getOrDefault("endpoint","");
        String bucket=params.getOrDefault("bucket","");
        String base=params.getOrDefault("basePath","");
        if(endpoint.isBlank() || bucket.isBlank()) throw new ServiceException("请补齐存储端点和存储桶");
        String access=snapshot.secrets().get("accessKey"),secret=snapshot.secrets().get("secretKey");
        if(access==null || access.isBlank() || secret==null || secret.isBlank()) throw new ServiceException("请配置存储访问密钥");
        var config=new FileStorageProperties.MinioConfig();
        config.setPlatform(snapshot.platform()); config.setEndPoint(endpoint);
        config.setBucketName(bucket);config.setBasePath(base.isEmpty() || base.endsWith("/")?base:base+"/");
        config.setDomain("");config.setAccessKey(access);config.setSecretKey(secret);
        return config;
    }
    public void validateParameters(java.util.Map<String,String> params) {
        if(!"minio".equals(params.get("provider"))) throw new ServiceException("当前运行时仅支持 MinIO");
        String endpoint=params.getOrDefault("endpoint","");
        if(!endpoint.isBlank()) {
        try {
            URI uri=URI.create(endpoint);
            if(!java.util.List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) { throw new ServiceException("MinIO 端点格式无效"); }
        }
        String bucket=params.getOrDefault("bucket","");
        String base=params.getOrDefault("basePath","");
        if(!bucket.isBlank() && !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || !base.matches("[A-Za-z0-9/_-]{0,200}") || base.startsWith("/"))
            throw new ServiceException("存储桶或对象前缀无效");
    }
    /** Probe before saving. No existing runtime client/default is modified on failure. */
    public void validate(Snapshot snapshot) {
        if(!snapshot.enabled()) return;
        var config=configuration(snapshot);
        try {
            var http=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build();
            var client=MinioClient.builder().endpoint(config.getEndPoint()).credentials(config.getAccessKey(),config.getSecretKey()).httpClient(http).build();
            if(!client.bucketExists(BucketExistsArgs.builder().bucket(config.getBucketName()).build())) throw new ServiceException("存储桶不存在");
        } catch(ServiceException invalid) { throw invalid; }
        catch(Exception unavailable) { throw new ServiceException("存储连接验证失败，原配置保持生效"); }
    }
    private synchronized void register(Snapshot snapshot) {
        if(storage.getFileStorage(snapshot.platform())!=null) return;
        var config=configuration(snapshot);
        storage.getFileStorageList().add(new MinioFileStorage(config,new MinioFileStorageClientFactory(config)));
    }
    public String uploadPlatform(String requested) {
        var provider=source.getIfAvailable();
        var current=provider==null?java.util.Optional.<Snapshot>empty():provider.current("oss");
        if(current.isEmpty()) return requested; // Existing deployment configuration remains the source until first save.
        var snapshot=current.get();
        if(!snapshot.enabled()) throw new ServiceException("对象存储已停用");
        register(snapshot);return snapshot.platform();
    }
    public void prepareRead(FileInfo info) {
        String platform=info.getPlatform();
        if(platform==null || !platform.startsWith("business-oss-")) return;
        if(storage.getFileStorage(platform)!=null) return;
        try {
            int revision=Integer.parseInt(platform.substring("business-oss-".length()));
            var provider=source.getIfAvailable();
            if(provider==null) throw new ServiceException("历史存储配置不可用");
            var snapshot=provider.historical("oss",revision).orElseThrow(()->new ServiceException("历史存储配置不存在"));
            register(snapshot);
        } catch(NumberFormatException invalid) { throw new ServiceException("文件存储平台无效"); }
    }
    public boolean deploymentEnabled() { return storage.getFileStorage()!=null; }
}
