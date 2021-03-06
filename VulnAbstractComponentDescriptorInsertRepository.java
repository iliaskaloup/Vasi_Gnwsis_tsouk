/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.sql.component;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.thingsboard.server.common.data.UUIDConverter;
import org.thingsboard.server.dao.model.sql.ComponentDescriptorEntity;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

@Slf4j
public abstract class AbstractComponentDescriptorInsertRepository implements ComponentDescriptorInsertRepository {

    @PersistenceContext
    protected EntityManager entityManager;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    protected ComponentDescriptorEntity saveAndGet(ComponentDescriptorEntity entity, String insertOrUpdateOnPrimaryKeyConflict, String insertOrUpdateOnUniqueKeyConflict) {
        ComponentDescriptorEntity componentDescriptorEntity = null;
        TransactionStatus insertTransaction = getTransactionStatus(TransactionDefinition.PROPAGATION_REQUIRED);
        try {
            componentDescriptorEntity = processSaveOrUpdate(entity, insertOrUpdateOnPrimaryKeyConflict);
            transactionManager.commit(insertTransaction);
        } catch (Throwable throwable) {
            transactionManager.rollback(insertTransaction);
            if (throwable.getCause() instanceof ConstraintViolationException) {
                log.trace("Insert request leaded in a violation of a defined integrity constraint {} for Component Descriptor with id {}, name {} and entityType {}", throwable.getMessage(), entity.getId(), entity.getName(), entity.getType());
                TransactionStatus transaction = getTransactionStatus(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    componentDescriptorEntity = processSaveOrUpdate(entity, insertOrUpdateOnUniqueKeyConflict);
                } catch (Throwable th) {
                    log.trace("Could not execute the update statement for Component Descriptor with id {}, name {} and entityType {}", entity.getId(), entity.getName(), entity.getType());
                    transactionManager.rollback(transaction);
                }
                transactionManager.commit(transaction);
            } else {
                log.trace("Could not execute the insert statement for Component Descriptor with id {}, name {} and entityType {}", entity.getId(), entity.getName(), entity.getType());
            }
        }
        return componentDescriptorEntity;
    }

    @Modifying
    protected abstract ComponentDescriptorEntity doProcessSaveOrUpdate(ComponentDescriptorEntity entity, String query);

    protected Query getQuery(ComponentDescriptorEntity entity, String query) {
        return entityManager.createNativeQuery(query, ComponentDescriptorEntity.class)
                .setParameter("id", UUIDConverter.fromTimeUUID(entity.getId()))
                .setParameter("actions", entity.getActions())
                .setParameter("clazz", entity.getClazz())
                .setParameter("configuration_descriptor", entity.getConfigurationDescriptor().toString())
                .setParameter("name", entity.getName())
                .setParameter("scope", entity.getScope().name())
                .setParameter("search_text", entity.getSearchText())
                .setParameter("type", entity.getType().name());
    }

    private ComponentDescriptorEntity processSaveOrUpdate(ComponentDescriptorEntity entity, String query) {
        return doProcessSaveOrUpdate(entity, query);
    }

    private TransactionStatus getTransactionStatus(int propagationRequired) {
        DefaultTransactionDefinition insertDefinition = new DefaultTransactionDefinition();
        insertDefinition.setPropagationBehavior(propagationRequired);
        return transactionManager.getTransaction(insertDefinition);
    }
}