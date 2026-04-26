/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;
    private final KbAccessService kbAccessService;
    private final SysDeptMapper sysDeptMapper;

    @Transactional
    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        // 名称重复校验
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        // 集合名称重复校验
        Long collectionCount = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, requestParam.getCollectionName())
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (collectionCount > 0) {
            throw new ServiceException("集合名称已存在：" + requestParam.getCollectionName());
        }

        // 解析 dept_id（授权与归属逻辑收归 KbAccessService）
        String effectiveDeptId = kbAccessService.resolveCreateKbDeptId(requestParam.getDeptId());

        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .embeddingModel(requestParam.getEmbeddingModel())
                .collectionName(requestParam.getCollectionName())
                .deptId(effectiveDeptId)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .deleted(0)
                .build();

        knowledgeBaseMapper.insert(kbDO);

        String bucketName = requestParam.getCollectionName();
        fileStorageService.ensureBucket(bucketName);

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .remark(requestParam.getName())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        return String.valueOf(kbDO.getId());
    }

    /**
     * Public write path with <b>no production caller</b> as of PR1. Deferred to a
     * future PR — when an HTTP / MQ caller is introduced, add
     * {@code kbAccessService.checkManageAccess(requestParam.getId())} as the
     * first line to establish the trust boundary, mirroring rename/delete.
     *
     * <p>Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §2.6
     */
    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在：" + requestParam.getId());
        }

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new ClientException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        kbAccessService.checkManageAccess(kbId);
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }

        // 名称重复校验（排除当前知识库）
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getName, name)
                        .ne(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId) {
        kbAccessService.checkManageAccess(kbId);
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount != null && docCount > 0) {
            throw new ClientException("当前知识库下还有文档，请删除文档");
        }

        kbDO.setDeleted(1);
        kbDO.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.deleteById(kbDO);
        int unbound = kbAccessService.unbindAllRolesFromKb(kbId);
        log.info("KB 软删 + role-KB 解绑完成, kbId={}, unbound={}", kbId, unbound);

        String collectionName = kbDO.getCollectionName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupExternalResources(kbId, collectionName);
                }
            });
            return;
        }
        cleanupExternalResources(kbId, collectionName);
    }

    private void cleanupExternalResources(String kbId, String collectionName) {
        try {
            vectorStoreAdmin.dropVectorSpace(
                    VectorSpaceId.builder().logicalName(collectionName).build());
            log.info("KB 向量空间已删除, kbId={}, collection={}", kbId, collectionName);
        } catch (Exception ex) {
            log.error("KB 向量空间删除失败（需运维介入）, kbId={}, collection={}", kbId, collectionName, ex);
        }
        try {
            fileStorageService.deleteBucket(collectionName);
            log.info("KB 存储桶已删除, kbId={}, bucket={}", kbId, collectionName);
        } catch (Exception ex) {
            log.error("KB 存储桶删除失败（需运维介入）, kbId={}, bucket={}", kbId, collectionName, ex);
        }
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        kbAccessService.checkAccess(kbId);
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        KnowledgeBaseVO vo = BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
        if (kbDO.getDeptId() != null) {
            SysDeptDO dept = sysDeptMapper.selectById(kbDO.getDeptId());
            if (dept != null) {
                vo.setDeptName(dept.getDeptName());
            }
        }
        return vo;
    }

    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam, AccessScope scope) {
        if (scope instanceof AccessScope.Ids ids && ids.kbIds().isEmpty()) {
            return new Page<>(requestParam.getCurrent(), requestParam.getSize(), 0);
        }

        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .eq(KnowledgeBaseDO::getDeleted, 0)
                .orderByDesc(KnowledgeBaseDO::getUpdateTime);
        if (scope instanceof AccessScope.Ids ids) {
            queryWrapper.in(KnowledgeBaseDO::getId, ids.kbIds());
        } else if (!(scope instanceof AccessScope.All)) {
            throw new IllegalStateException("Unsupported AccessScope: " + scope);
        }

        Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
        Map<String, String> deptNameMap = new HashMap<>();
        Map<String, Long> docCountMap = new HashMap<>();
        if (CollUtil.isNotEmpty(result.getRecords())) {
            List<String> kbIds = result.getRecords().stream()
                    .map(KnowledgeBaseDO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            List<String> deptIds = result.getRecords().stream()
                    .map(KnowledgeBaseDO::getDeptId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!deptIds.isEmpty()) {
                sysDeptMapper.selectBatchIds(deptIds).forEach(d -> deptNameMap.put(d.getId(), d.getDeptName()));
            }
            if (!kbIds.isEmpty()) {
                List<Map<String, Object>> rows = knowledgeDocumentMapper.selectMaps(
                        Wrappers.query(KnowledgeDocumentDO.class)
                                .select("kb_id", "COUNT(1) AS doc_count")
                                .in("kb_id", kbIds)
                                .eq("deleted", 0)
                                .groupBy("kb_id")
                );
                for (Map<String, Object> row : rows) {
                    Object kbIdValue = row.get("kb_id");
                    Object countValue = row.get("doc_count");
                    if (kbIdValue == null) {
                        continue;
                    }

                    String kbId = kbIdValue instanceof Number
                            ? String.valueOf(((Number) kbIdValue).longValue())
                            : kbIdValue.toString();
                    Long count = countValue instanceof Number
                            ? ((Number) countValue).longValue()
                            : countValue != null ? Long.parseLong(countValue.toString()) : 0L;
                    docCountMap.put(kbId, count);
                }
            }
        }
        return result.convert(each -> {
            KnowledgeBaseVO vo = BeanUtil.toBean(each, KnowledgeBaseVO.class);
            Long docCount = docCountMap.get(each.getId());
            vo.setDocumentCount(docCount != null ? docCount : 0L);
            if (each.getDeptId() != null) {
                vo.setDeptName(deptNameMap.get(each.getDeptId()));
            }
            return vo;
        });
    }
}
