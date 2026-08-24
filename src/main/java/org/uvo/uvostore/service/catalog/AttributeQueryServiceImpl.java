package org.uvo.uvostore.service.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.repository.AttributeRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.List;

@Service
public class AttributeQueryServiceImpl implements AttributeQueryService {

    private final AttributeRepository attributeRepository;

    public AttributeQueryServiceImpl(AttributeRepository attributeRepository) {
        this.attributeRepository = attributeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttributeDto> listAll() {
        return attributeRepository.findByStoreIdOrderByNameAsc(TenantContext.requireStoreId()).stream()
                .map(AttributeDtoMapper::toDto)
                .toList();
    }
}
