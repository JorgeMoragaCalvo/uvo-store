package org.uvo.uvostore.service.settings;

import java.util.List;

public interface HomeBannerService {
    List<HomeBannerDto> list(String search);
    HomeBannerDto get(Long id);
    HomeBannerDto create(HomeBannerCommand command);
    HomeBannerDto update(Long id, HomeBannerCommand command);
    void delete(Long id);
    HomeBannerDto toggleActive(Long id);
    void reorder(List<Long> orderedIds);
}
