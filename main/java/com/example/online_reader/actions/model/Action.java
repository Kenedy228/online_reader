package com.example.online_reader.actions.model;

import com.example.online_reader.enums.ActionType;
import com.example.online_reader.user.model.User;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "actions")
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Integer id;
    @Column(unique = true, nullable = false)
    private String label;
    @Column(unique = true, nullable = false)
    private String description;

//    @ManyToMany(mappedBy = "actions", cascade = CascadeType.MERGE)
//    private final List<User> users = new ArrayList<>();

    public Action() {

    }

    public Action(ActionType action) {
        this.label = action.getLabel();
        this.description = action.getDescription();
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public void setAction(ActionType action) {
        this.label = action.getLabel();
        this.description = action.getDescription();
    }

//    public void setUser(User user) {
//        users.add(user);
//        user.setAction(this);
//    }
//
//    public List<User> getUsers() {
//        return users;
//    }
}