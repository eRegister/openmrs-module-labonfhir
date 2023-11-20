CREATE DEFINER=CURRENT_USER() TRIGGER openmrs_orders_AFTER_INSERT 
AFTER INSERT ON orders 
FOR EACH ROW
BEGIN
	IF NEW.order_type_id = 3 THEN 
		INSERT INTO openmrs.test_order (order_id) VALUES (NEW.order_id);
	END IF;
END    
