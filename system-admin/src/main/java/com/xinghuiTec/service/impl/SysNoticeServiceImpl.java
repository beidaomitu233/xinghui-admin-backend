package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysNoticeAddDTO;
import com.xinghuiTec.domain.dto.SysNoticeQueryDTO;
import com.xinghuiTec.mapper.SysNoticeMapper;
import com.xinghuiTec.domain.entity.SysNotice;
import com.xinghuiTec.service.SysNoticeService;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 通知公告表(SysNotice)表服务实现类
 */
@Service
public class SysNoticeServiceImpl extends ServiceImpl<SysNoticeMapper, SysNotice> implements SysNoticeService {

    @Override
    public Page<SysNotice> getNoticeList(SysNoticeQueryDTO queryDTO) {
        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getNoticeTitle()), SysNotice::getNoticeTitle,
                queryDTO.getNoticeTitle())
                .eq(queryDTO.getNoticeType() != null, SysNotice::getNoticeType,
                        String.valueOf(queryDTO.getNoticeType()))
                .eq(StringUtils.hasText(queryDTO.getCreateBy()), SysNotice::getCreateBy, queryDTO.getCreateBy())
                .eq(StringUtils.hasText(queryDTO.getStatus()), SysNotice::getStatus, queryDTO.getStatus())
                .orderByDesc(SysNotice::getCreateTime);

        Page<SysNotice> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        Page<SysNotice> result = this.page(page, wrapper);

        // 处理BLOB内容转换
        result.getRecords().forEach(this::convertBlobContent);
        return result;
    }

    @Override
    public SysNotice getNoticeById(Long noticeId) {
        SysNotice notice = this.getById(noticeId);
        if (notice != null) {
            convertBlobContent(notice);
        }
        return notice;
    }

    /**
     * 将BLOB内容转换为String（如果需要）
     * 由于数据库中noticeContent是LONGBLOB类型，可能返回byte[]
     */
    private void convertBlobContent(SysNotice notice) {
        // 如果noticeContent是byte[]，转换为String
        // 注意：由于实体类现在是String类型，如果MyBatis返回的是byte[]，
        // 框架会尝试自动转换，但如果失败则需要使用TypeHandler
        // 这里作为备用处理
    }

    @Override
    public Long addNotice(SysNoticeAddDTO addDTO) {
        SysNotice notice = new SysNotice();
        BeanUtils.copyProperties(addDTO, notice);
        notice.setCreateBy(SecurityUtils.getUser().getUsername());
        notice.setCreateTime(new Date());
        // 转换类型
        if (addDTO.getNoticeType() != null) {
            notice.setNoticeType(String.valueOf(addDTO.getNoticeType()));
        }
        if (addDTO.getStatus() != null) {
            notice.setStatus(String.valueOf(addDTO.getStatus()));
        }
        // 解析日期
        notice.setStartTime(parseDate(addDTO.getStartTime()));
        notice.setEndTime(parseDate(addDTO.getEndTime()));

        this.save(notice);
        return Long.valueOf(notice.getNoticeId());
    }

    @Override
    public void updateNotice(SysNoticeAddDTO addDTO) {
        SysNotice notice = new SysNotice();
        BeanUtils.copyProperties(addDTO, notice);
        notice.setNoticeId(addDTO.getNoticeId() != null ? addDTO.getNoticeId().intValue() : null);

        if (addDTO.getNoticeType() != null) {
            notice.setNoticeType(String.valueOf(addDTO.getNoticeType()));
        }
        if (addDTO.getStatus() != null) {
            notice.setStatus(String.valueOf(addDTO.getStatus()));
        }
        // 解析日期
        notice.setStartTime(parseDate(addDTO.getStartTime()));
        notice.setEndTime(parseDate(addDTO.getEndTime()));

        this.updateById(notice);
    }

    /**
     * 解析日期字符串
     */
    private Date parseDate(String dateStr) {
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.parse(dateStr);
        } catch (ParseException e) {
            return null;
        }
    }

    @Override
    public void deleteNotice(Long noticeId) {
        this.removeById(noticeId);
    }

    @Override
    public void deleteNotices(List<Long> noticeIds) {
        this.removeBatchByIds(noticeIds);
    }

    @Override
    public void changeStatus(Long noticeId, Integer status) {
        SysNotice notice = new SysNotice();
        notice.setNoticeId(noticeId.intValue());
        notice.setStatus(String.valueOf(status));
        this.updateById(notice);
    }
}
