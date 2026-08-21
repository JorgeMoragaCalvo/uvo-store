CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    slug VARCHAR NOT NULL UNIQUE,
    description TEXT,
    image VARCHAR,
    icon VARCHAR,
    active BOOLEAN NOT NULL,
    is_featured BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    products_count INTEGER NOT NULL,
    parent_id BIGINT REFERENCES categories(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE attributes (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    slug VARCHAR NOT NULL UNIQUE,
    type VARCHAR NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE attribute_values (
    id BIGSERIAL PRIMARY KEY,
    attribute_id BIGINT NOT NULL REFERENCES attributes(id) ON DELETE CASCADE,
    value VARCHAR NOT NULL,
    slug VARCHAR NOT NULL,
    color_hex VARCHAR,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT attribute_values_attribute_id_slug_key UNIQUE (attribute_id, slug)
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR UNIQUE,
    name VARCHAR NOT NULL,
    slug VARCHAR NOT NULL UNIQUE,
    short_description TEXT,
    description TEXT,
    product_type VARCHAR NOT NULL,
    price NUMERIC(10,2),
    sale_price NUMERIC(10,2),
    stock INTEGER NOT NULL,
    weight NUMERIC(10,2),
    manage_stock BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    featured BOOLEAN NOT NULL,
    is_featured BOOLEAN NOT NULL,
    is_new BOOLEAN NOT NULL,
    is_on_sale BOOLEAN NOT NULL,
    new_until DATE,
    sale_starts_at TIMESTAMPTZ,
    sale_ends_at TIMESTAMPTZ,
    discount_percentage INTEGER,
    sort_order INTEGER NOT NULL,
    views_count INTEGER NOT NULL,
    sales_count INTEGER NOT NULL,
    meta_title VARCHAR,
    meta_description TEXT,
    images JSONB,
    featured_image VARCHAR,
    pos_product_id VARCHAR,
    category_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE product_images (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_path VARCHAR NOT NULL,
    alt_text VARCHAR,
    sort_order INTEGER NOT NULL,
    is_featured BOOLEAN NOT NULL,
    type VARCHAR NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE product_variations (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku VARCHAR NOT NULL UNIQUE,
    price NUMERIC(10,2) NOT NULL,
    compare_at_price NUMERIC(10,2),
    stock INTEGER NOT NULL,
    weight NUMERIC(8,2),
    length NUMERIC(19,2),
    width NUMERIC(19,2),
    height NUMERIC(19,2),
    image VARCHAR,
    active BOOLEAN NOT NULL,
    pos_product_id VARCHAR,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE product_variation_attributes (
    id BIGSERIAL PRIMARY KEY,
    product_variation_id BIGINT NOT NULL REFERENCES product_variations(id) ON DELETE CASCADE,
    attribute_id BIGINT NOT NULL REFERENCES attributes(id) ON DELETE CASCADE,
    attribute_value_id BIGINT NOT NULL REFERENCES attribute_values(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT pva_var_attr_uniq UNIQUE (product_variation_id, attribute_id)
);

CREATE INDEX pva_var_attrval_idx ON product_variation_attributes (product_variation_id, attribute_value_id);
