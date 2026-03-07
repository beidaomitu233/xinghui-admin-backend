package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysFileQueryDTO;
import com.xinghuiTec.domain.entity.SysFile;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件记录表(SysFile)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysFileService extends IService<SysFile> {

    SysFile uploadFile(MultipartFile file);

    Page<SysFile> getFileList(SysFileQueryDTO queryDTO);

    void deleteFile(String fileId);

    void deleteFiles(java.util.List<String> fileIds);

    void downloadFile(String fileId, HttpServletResponse response);

    String getFileUrl(String fileId);
}
