package com.olist.datasets.service;

import com.olist.datasets.dto.CleaningRuleQuery;
import com.olist.datasets.dto.CleaningRuleRequest;
import com.olist.datasets.vo.CleaningRuleVO;
import com.olist.datasets.vo.PageVO;
import java.util.UUID;

public interface RuleLibraryService {

    PageVO<CleaningRuleVO> list(CleaningRuleQuery query);

    CleaningRuleVO get(UUID id);

    CleaningRuleVO create(CleaningRuleRequest request);

    CleaningRuleVO update(UUID id, CleaningRuleRequest request);

    CleaningRuleVO setEnabled(UUID id, boolean enabled);

    void delete(UUID id);
}
