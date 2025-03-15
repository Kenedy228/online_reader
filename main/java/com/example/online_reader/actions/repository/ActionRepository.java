package com.example.online_reader.actions.repository;

import com.example.online_reader.actions.model.Action;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionRepository extends JpaRepository<Action, Integer> {
}
