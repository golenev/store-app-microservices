-- Percentages consumed by the legacy STORE API; task 3 introduces quote rules.
INSERT INTO tariffs (product_type, markup_coefficient) VALUES
    ('food_100', 1), ('food_300', 3), ('food_500', 5), ('food_1000', 10),
    ('not_food_100', 5), ('not_food_500', 10), ('not_food_1000', 20);
