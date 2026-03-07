package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysNoticeAddDTO;
import com.xinghuiTec.domain.dto.SysNoticeQueryDTO;
import com.xinghuiTec.domain.entity.SysNotice;

import java.util.List;

/**
 * 通知公告表(SysNotice)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysNoticeService extends IService<SysNotice> {

    Page<SysNotice> getNoticeList(SysNoticeQueryDTO queryDTO);

    SysNotice getNoticeById(Long noticeId);

    Long addNotice(SysNoticeAddDTO addDTO);

    void updateNotice(SysNoticeAddDTO addDTO);

    void deleteNotice(Long noticeId);

    void deleteNotices(List<Long> noticeIds);

    void changeStatus(Long noticeId, Integer status);
}
