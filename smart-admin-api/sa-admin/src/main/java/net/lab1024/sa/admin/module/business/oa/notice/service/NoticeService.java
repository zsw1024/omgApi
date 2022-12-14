package net.lab1024.sa.admin.module.business.oa.notice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.lab1024.sa.admin.module.business.oa.notice.constant.NoticeVisibleRangeDataTypeEnum;
import net.lab1024.sa.admin.module.business.oa.notice.dao.NoticeDao;
import net.lab1024.sa.admin.module.business.oa.notice.domain.entity.NoticeEntity;
import net.lab1024.sa.admin.module.business.oa.notice.domain.form.NoticeAddForm;
import net.lab1024.sa.admin.module.business.oa.notice.domain.form.NoticeQueryForm;
import net.lab1024.sa.admin.module.business.oa.notice.domain.form.NoticeUpdateForm;
import net.lab1024.sa.admin.module.business.oa.notice.domain.form.NoticeVisibleRangeForm;
import net.lab1024.sa.admin.module.business.oa.notice.domain.vo.NoticeTypeVO;
import net.lab1024.sa.admin.module.business.oa.notice.domain.vo.NoticeUpdateFormVO;
import net.lab1024.sa.admin.module.business.oa.notice.domain.vo.NoticeVO;
import net.lab1024.sa.admin.module.business.oa.notice.domain.vo.NoticeVisibleRangeVO;
import net.lab1024.sa.admin.module.business.oa.notice.manager.NoticeManager;
import net.lab1024.sa.admin.module.system.department.dao.DepartmentDao;
import net.lab1024.sa.admin.module.system.department.domain.entity.DepartmentEntity;
import net.lab1024.sa.admin.module.system.department.domain.vo.DepartmentVO;
import net.lab1024.sa.admin.module.system.department.service.DepartmentService;
import net.lab1024.sa.admin.module.system.employee.dao.EmployeeDao;
import net.lab1024.sa.admin.module.system.employee.domain.entity.EmployeeEntity;
import net.lab1024.sa.common.common.constant.StringConst;
import net.lab1024.sa.common.common.domain.PageResult;
import net.lab1024.sa.common.common.domain.ResponseDTO;
import net.lab1024.sa.common.common.util.SmartBeanUtil;
import net.lab1024.sa.common.common.util.SmartPageUtil;
import net.lab1024.sa.common.module.support.datatracer.constant.DataTracerTypeEnum;
import net.lab1024.sa.common.module.support.datatracer.service.DataTracerService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ??????????????? ??????????????????
 *
 * @Author 1024???????????????-??????: ??????
 * @Date 2022-08-12 21:40:39
 * @Wechat zhuoda1024
 * @Email lab1024@163.com
 * @Copyright 1024??????????????? ??? https://1024lab.net ??????2012-2022
 */
@Service
public class NoticeService {

    @Autowired
    private NoticeDao noticeDao;

    @Autowired
    private NoticeManager noticeManager;

    @Autowired
    private EmployeeDao employeeDao;

    @Autowired
    private DepartmentDao departmentDao;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private NoticeTypeService noticeTypeService;

    @Autowired
    private DataTracerService dataTracerService;

    /**
     * ?????? ???????????????
     *
     * @param queryForm
     * @return
     */
    public PageResult<NoticeVO> query(NoticeQueryForm queryForm) {
        Page<?> page = SmartPageUtil.convert2PageQuery(queryForm);
        List<NoticeVO> list = noticeDao.query(page, queryForm);
        LocalDateTime now = LocalDateTime.now();
        list.forEach(e -> e.setPublishFlag(e.getPublishTime().isBefore(now)));
        return SmartPageUtil.convert2PageResult(page, list);
    }

    /**
     * ??????
     *
     * @param addForm
     * @return
     */
    public ResponseDTO<String> add(NoticeAddForm addForm) {
        // ???????????????????????????
        ResponseDTO<String> validate = this.checkAndBuildVisibleRange(addForm);
        if (!validate.getOk()) {
            return ResponseDTO.error(validate);
        }

        // build ??????
        NoticeEntity noticeEntity = SmartBeanUtil.copy(addForm, NoticeEntity.class);
        // ???????????????????????????????????? ????????? ??????
        if (!addForm.getScheduledPublishFlag()) {
            noticeEntity.setPublishTime(LocalDateTime.now());
        }
        // ????????????
        noticeManager.save(noticeEntity, addForm.getVisibleRangeList());
        return ResponseDTO.ok();
    }

    /**
     * ???????????????????????????
     *
     * @param form
     * @return
     */
    private ResponseDTO<String> checkAndBuildVisibleRange(NoticeAddForm form) {
        // ??????????????????
        NoticeTypeVO noticeType = noticeTypeService.getByNoticeTypeId(form.getNoticeTypeId());
        if (noticeType == null) {
            return ResponseDTO.userErrorParam("???????????????");
        }

        if (form.getAllVisibleFlag()) {
            return ResponseDTO.ok();
        }

        /**
         * ??????????????????
         * ?????????????????? ?????????????????????|??????
         */
        List<NoticeVisibleRangeForm> visibleRangeUpdateList = form.getVisibleRangeList();
        if (CollectionUtils.isEmpty(visibleRangeUpdateList)) {
            return ResponseDTO.userErrorParam("?????????????????????");
        }

        // ??????????????????-> ??????
        List<Long> employeeIdList = visibleRangeUpdateList.stream()
                .filter(e -> NoticeVisibleRangeDataTypeEnum.EMPLOYEE.equalsValue(e.getDataType()))
                .map(NoticeVisibleRangeForm::getDataId)
                .distinct().collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(employeeIdList)) {
            employeeIdList = employeeIdList.stream().distinct().collect(Collectors.toList());
            List<Long> dbEmployeeIdList = employeeDao.selectBatchIds(employeeIdList).stream().map(EmployeeEntity::getEmployeeId).collect(Collectors.toList());
            Collection<Long> subtract = CollectionUtils.subtract(employeeIdList, dbEmployeeIdList);
            if (subtract.size() > 0) {
                return ResponseDTO.userErrorParam("??????id????????????" + subtract);
            }
        }

        // ??????????????????-> ??????
        List<Long> deptIdList = visibleRangeUpdateList.stream()
                .filter(e -> NoticeVisibleRangeDataTypeEnum.DEPARTMENT.equalsValue(e.getDataType()))
                .map(NoticeVisibleRangeForm::getDataId)
                .distinct().collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(deptIdList)) {
            deptIdList = deptIdList.stream().distinct().collect(Collectors.toList());
            List<Long> dbDeptIdList = departmentDao.selectBatchIds(deptIdList).stream().map(DepartmentEntity::getDepartmentId).collect(Collectors.toList());
            Collection<Long> subtract = CollectionUtils.subtract(deptIdList, dbDeptIdList);
            if (subtract.size() > 0) {
                return ResponseDTO.userErrorParam("??????id????????????" + subtract);
            }
        }
        return ResponseDTO.ok();
    }


    /**
     * ??????
     *
     * @param updateForm
     * @return
     */
    public ResponseDTO<String> update(NoticeUpdateForm updateForm) {

        NoticeEntity oldNoticeEntity = noticeDao.selectById(updateForm.getNoticeId());
        if (oldNoticeEntity == null) {
            return ResponseDTO.userErrorParam("???????????????");
        }

        // ???????????????????????????
        ResponseDTO<String> res = this.checkAndBuildVisibleRange(updateForm);
        if (!res.getOk()) {
            return ResponseDTO.error(res);
        }

        // ??????
        NoticeEntity noticeEntity = SmartBeanUtil.copy(updateForm, NoticeEntity.class);
        noticeManager.update(oldNoticeEntity, noticeEntity, updateForm.getVisibleRangeList());
        return ResponseDTO.ok();
    }


    /**
     * ??????
     *
     * @param noticeId
     * @return
     */
    public ResponseDTO<String> delete(Long noticeId) {
        NoticeEntity noticeEntity = noticeDao.selectById(noticeId);
        if (null == noticeEntity || noticeEntity.getDeletedFlag()) {
            return ResponseDTO.userErrorParam("?????????????????????");
        }
        // ??????????????????
        noticeDao.updateDeletedFlag(noticeId);
        dataTracerService.delete(noticeId, DataTracerTypeEnum.OA_NOTICE);
        return ResponseDTO.ok();
    }

    /**
     * ??????????????????????????????
     *
     * @param noticeId
     * @return
     */
    public NoticeUpdateFormVO getUpdateFormVO(Long noticeId) {
        NoticeEntity noticeEntity = noticeDao.selectById(noticeId);
        if (null == noticeEntity) {
            return null;
        }

        NoticeUpdateFormVO updateFormVO = SmartBeanUtil.copy(noticeEntity, NoticeUpdateFormVO.class);
        if (!updateFormVO.getAllVisibleFlag()) {
            List<NoticeVisibleRangeVO> noticeVisibleRangeList = noticeDao.queryVisibleRange(noticeId);
            List<Long> employeeIdList = noticeVisibleRangeList.stream().filter(e -> NoticeVisibleRangeDataTypeEnum.EMPLOYEE.getValue().equals(e.getDataType()))
                    .map(NoticeVisibleRangeVO::getDataId)
                    .collect(Collectors.toList());

            Map<Long, EmployeeEntity> employeeMap = null;
            if (CollectionUtils.isNotEmpty(employeeIdList)) {
                employeeMap = employeeDao.selectBatchIds(employeeIdList).stream().collect(Collectors.toMap(EmployeeEntity::getEmployeeId, Function.identity()));
            } else {
                employeeMap = new HashMap<>();
            }
            for (NoticeVisibleRangeVO noticeVisibleRange : noticeVisibleRangeList) {
                if (noticeVisibleRange.getDataType().equals(NoticeVisibleRangeDataTypeEnum.EMPLOYEE.getValue())) {
                    EmployeeEntity employeeEntity = employeeMap.get(noticeVisibleRange.getDataId());
                    noticeVisibleRange.setDataName(employeeEntity == null ? StringConst.EMPTY : employeeEntity.getActualName());
                } else {
                    DepartmentVO departmentVO = departmentService.getDepartmentById(noticeVisibleRange.getDataId());
                    noticeVisibleRange.setDataName(departmentVO == null ? StringConst.EMPTY : departmentVO.getName());
                }
            }
            updateFormVO.setVisibleRangeList(noticeVisibleRangeList);
        }
        return updateFormVO;
    }
}
