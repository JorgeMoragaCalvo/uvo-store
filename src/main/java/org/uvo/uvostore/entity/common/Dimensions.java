package org.uvo.uvostore.entity.common;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Dimensions {

    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
}
