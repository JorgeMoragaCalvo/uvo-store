package org.uvo.uvostore.service.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Attribute;
import org.uvo.uvostore.entity.catalog.AttributeValue;
import org.uvo.uvostore.entity.catalog.enums.AttributeType;
import org.uvo.uvostore.repository.AttributeRepository;
import org.uvo.uvostore.repository.AttributeValueRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AttributeServiceImpl implements AttributeService {

    private final AttributeRepository attributeRepository;
    private final AttributeValueRepository attributeValueRepository;

    public AttributeServiceImpl(AttributeRepository attributeRepository, AttributeValueRepository attributeValueRepository) {
        this.attributeRepository = attributeRepository;
        this.attributeValueRepository = attributeValueRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttributeDto> listAll() {
        return attributeRepository.findByStoreIdOrderByNameAsc(TenantContext.requireStoreId()).stream()
                .map(AttributeDtoMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public AttributeDto createAttribute(AttributeCommand command) {
        Attribute attribute = new Attribute();
        attribute.setStore(TenantContext.requireCurrent());
        applyCommonFields(attribute, command);
        return AttributeDtoMapper.toDto(attributeRepository.save(attribute));
    }

    @Override
    @Transactional
    public AttributeDto updateAttribute(Long id, AttributeCommand command) {
        Attribute attribute = findAttributeOrThrow(id);
        applyCommonFields(attribute, command);
        return AttributeDtoMapper.toDto(attributeRepository.save(attribute));
    }

    @Override
    @Transactional
    public void deleteAttribute(Long id) {
        Attribute attribute = findAttributeOrThrow(id);
        if (!attribute.getValues().isEmpty()) {
            throw new IllegalStateException("Primero elimina todos los valores del atributo");
        }
        attributeRepository.delete(attribute);
    }

    @Override
    @Transactional
    public AttributeDto createValue(AttributeValueCommand command) {
        Attribute attribute = findAttributeOrThrow(command.attributeId());

        AttributeValue value = new AttributeValue();
        value.setStore(TenantContext.requireCurrent());
        value.setAttribute(attribute);
        applyValueFields(value, command);
        attributeValueRepository.save(value);

        return AttributeDtoMapper.toDto(findAttributeOrThrow(command.attributeId()));
    }

    @Override
    @Transactional
    public AttributeDto updateValue(Long valueId, AttributeValueCommand command) {
        AttributeValue value = attributeValueRepository.findById(valueId)
                .filter(v -> v.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Attribute value " + valueId + " not found"));
        applyValueFields(value, command);
        attributeValueRepository.save(value);

        return AttributeDtoMapper.toDto(findAttributeOrThrow(value.getAttribute().getId()));
    }

    @Override
    @Transactional
    public AttributeDto deleteValue(Long valueId) {
        AttributeValue value = attributeValueRepository.findById(valueId)
                .filter(v -> v.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Attribute value " + valueId + " not found"));
        Long attributeId = value.getAttribute().getId();
        attributeValueRepository.delete(value);
        return AttributeDtoMapper.toDto(findAttributeOrThrow(attributeId));
    }

    private void applyCommonFields(Attribute attribute, AttributeCommand command) {
        attribute.setName(command.name());
        attribute.setSlug(command.slug());
        attribute.setType(AttributeType.valueOf(command.type().toUpperCase()));
    }

    private void applyValueFields(AttributeValue value, AttributeValueCommand command) {
        value.setValue(command.value());
        value.setSlug(command.slug());
        value.setColorHex(command.colorHex());
        value.setSortOrder(command.sortOrder());
    }

    private Attribute findAttributeOrThrow(Long id) {
        return attributeRepository.findById(id)
                .filter(a -> a.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Attribute " + id + " not found"));
    }
}
